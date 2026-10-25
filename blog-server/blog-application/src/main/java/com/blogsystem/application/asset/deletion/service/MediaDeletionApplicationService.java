package com.blogsystem.application.asset.deletion.service;

import com.blogsystem.application.asset.deletion.command.MediaDeletionCommand;
import com.blogsystem.application.asset.deletion.result.MediaDeletionPreview;
import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionOperationRepository;
import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.asset.deletion.model.MediaDeletionException.Reason;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionTarget;
import com.blogsystem.domain.asset.deletion.policy.MediaDeletionPolicy;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceRepository;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence.Source;
import com.blogsystem.domain.music.adapter.repository.MusicTrackDeletionRepository;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoDeletionRepository;
import com.blogsystem.domain.surprise.model.SurpriseCatalogException;

import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Short database-only workflow. No provider, JSON catalogue, or publication client is injected. */
public final class MediaDeletionApplicationService {
    private final MediaDeletionAssetRepository assets;
    private final MediaDeletionOperationRepository operations;
    private final MusicTrackDeletionRepository music;
    private final SurpriseVideoDeletionRepository videos;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final String environmentId;
    private final boolean enabled;
    private final BooleanSupplier schemaReady;
    private final MediaReferenceRepository references;
    private final MediaDeletionPolicy policy = new MediaDeletionPolicy();

    public MediaDeletionApplicationService(
            MediaDeletionAssetRepository assets,
            MediaDeletionOperationRepository operations,
            MusicTrackDeletionRepository music,
            SurpriseVideoDeletionRepository videos,
            TransactionOperations transactions,
            Clock clock,
            String environmentId,
            boolean enabled,
            BooleanSupplier schemaReady) {
        this(assets, operations, music, videos, transactions, clock, environmentId, enabled, schemaReady,
                asset -> new MediaReferenceEvidence(EnumSet.allOf(Source.class), Set.of(), List.of(), EnumSet.allOf(Source.class)));
    }

    public MediaDeletionApplicationService(
            MediaDeletionAssetRepository assets,
            MediaDeletionOperationRepository operations,
            MusicTrackDeletionRepository music,
            SurpriseVideoDeletionRepository videos,
            TransactionOperations transactions,
            Clock clock,
            String environmentId,
            boolean enabled,
            BooleanSupplier schemaReady,
            MediaReferenceRepository references) {
        this.assets = assets;
        this.operations = operations;
        this.music = music;
        this.videos = videos;
        this.transactions = transactions;
        this.clock = clock;
        this.environmentId = environmentId;
        this.enabled = enabled;
        this.schemaReady = schemaReady;
        this.references = Objects.requireNonNull(references);
        if (enabled && (environmentId == null || !environmentId.matches("[a-z][a-z0-9-]{0,31}")))
            throw new IllegalArgumentException("删除环境标识未配置");
    }

    public boolean submissionEnabled() {
        return enabled && schemaReady.getAsBoolean();
    }

    public MediaDeletionPreview preview(long actorId, MediaDeletionTarget target) {
        requireRead(actorId);
        try {
            State state = state(target);
            List<MediaDeletionPreview.Candidate> candidates = new ArrayList<>();
            for (UUID id : state.assetIds().stream().sorted().toList()) {
                var asset =
                        assets.findById(id)
                                .orElseThrow(() -> new MediaDeletionException(Reason.CONFLICT));
                candidates.add(
                        new MediaDeletionPreview.Candidate(
                                id,
                                asset.asset().kind(),
                                asset.asset().version(),
                                asset.ownership().scope(),
                                asset.ownership().exclusiveEnvironment(),
                                decision(asset),
                                references.inspect(asset.asset())));
            }
            boolean coverageComplete = !candidates.isEmpty() && candidates.stream().allMatch(candidate ->
                    candidate.referenceEvidence().requiredSources().containsAll(EnumSet.allOf(Source.class))
                            && candidate.referenceEvidence().complete());
            return new MediaDeletionPreview(
                    target, state.version(), candidates, coverageComplete, environmentId);
        } catch (RuntimeException error) {
            throw safe(error);
        }
    }

    public MediaDeletionOperation submit(long actorId, String key, MediaDeletionCommand command) {
        requireWrite(actorId);
        if (key == null
                || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new MediaDeletionException(Reason.INVALID_REQUEST);
        String fingerprint = fingerprint(command);
        try {
            return transactions.execute(
                    tx -> {
                        var prior = operations.findByActorAndKey(actorId, key);
                        if (prior.isPresent()) return replay(prior.get(), fingerprint);
                        State initial = state(command.target());
                        requireVersion(initial, command);
                        List<MediaDeletionAssetSnapshot> locked =
                                assets.lockByIds(initial.assetIds());
                        State current = state(command.target());
                        requireVersion(current, command);
                        if (locked.size() != current.assetIds().size())
                            throw new MediaDeletionException(Reason.CONFLICT);
                        List<MediaDeletionOperation.Item> items = new ArrayList<>();
                        for (var asset : locked) {
                            if (!Objects.equals(
                                    command.assetVersions().get(asset.asset().id()),
                                    asset.asset().version()))
                                throw new MediaDeletionException(Reason.CONFLICT);
                            var decision = decision(asset);
                            if (decision.status() != MediaDeletionOperation.ItemStatus.RETAINED)
                                throw new MediaDeletionException(Reason.UNAVAILABLE);
                            items.add(
                                    new MediaDeletionOperation.Item(
                                            UUID.randomUUID(),
                                            asset.asset().id(),
                                            asset.asset().kind(),
                                            asset.asset().version(),
                                            asset.ownership(),
                                            decision.status(),
                                            decision.reason(),
                                            null));
                        }
                        Instant now = clock.instant();
                        var target = command.target();
                        switch (target.kind()) {
                            case MUSIC_TRACK ->
                                    music.softDelete(target.id(), command.expectedVersion(), now);
                            case MUSIC_MEDIA ->
                                    music.unbindMedia(
                                            target.id(),
                                            MusicMediaKind.valueOf(target.slot().name()),
                                            current.selectedAssetId(),
                                            command.expectedVersion(),
                                            now);
                            case SURPRISE_VIDEO ->
                                    videos.softDelete(target.id(), command.expectedVersion(), now);
                            case IMAGE_ASSET ->
                                    throw new MediaDeletionException(Reason.OPERATION_NOT_READY);
                        }
                        return operations.insert(
                                new MediaDeletionOperation(
                                        UUID.randomUUID(),
                                        actorId,
                                        key,
                                        fingerprint,
                                        target,
                                        command.expectedVersion(),
                                        now,
                                        items));
                    });
        } catch (RuntimeException error) {
            var failure = safe(error);
            if (failure.reason() == Reason.CONFLICT || failure.reason() == Reason.NOT_FOUND) {
                var prior = transactions.execute(tx -> operations.findByActorAndKey(actorId, key));
                if (prior != null && prior.isPresent()) return replay(prior.get(), fingerprint);
            }
            throw failure;
        }
    }

    public MediaDeletionOperation get(UUID id, long actorId) {
        requireRead(actorId);
        return operations
                .findById(id, actorId)
                .orElseThrow(() -> new MediaDeletionException(Reason.NOT_FOUND));
    }

    public MediaDeletionOperationRepository.Page history(long actorId, int offset, int limit) {
        requireRead(actorId);
        return operations.findPage(actorId, offset, limit);
    }

    public MediaDeletionAssetSnapshot confirmOwnership(
            long actorId,
            UUID assetId,
            MediaDeletionOwnership.Scope scope,
            long expectedVersion,
            String note) {
        requireWrite(actorId);
        if (scope == null || scope == MediaDeletionOwnership.Scope.UNVERIFIED)
            throw new MediaDeletionException(Reason.INVALID_REQUEST);
        if (expectedVersion == Long.MAX_VALUE) throw new MediaDeletionException(Reason.CONFLICT);
        Instant now = clock.instant();
        var ownership =
                new MediaDeletionOwnership(
                        scope,
                        scope == MediaDeletionOwnership.Scope.EXCLUSIVE ? environmentId : null,
                        actorId,
                        now,
                        note);
        return transactions.execute(
                tx -> assets.confirmOwnership(assetId, ownership, expectedVersion, now));
    }

    private MediaDeletionPolicy.Decision decision(MediaDeletionAssetSnapshot asset) {
        return policy.evaluate(
                asset, new MediaDeletionPolicy.Evidence(environmentId, false, false, false));
    }

    private record State(long version, Set<UUID> assetIds, UUID selectedAssetId) {}

    private State state(MediaDeletionTarget target) {
        if (target.kind() == MediaDeletionTarget.Kind.IMAGE_ASSET)
            throw new MediaDeletionException(Reason.OPERATION_NOT_READY);
        if (target.kind() == MediaDeletionTarget.Kind.SURPRISE_VIDEO) {
            var row =
                    videos.findSnapshotById(target.id())
                            .orElseThrow(() -> new MediaDeletionException(Reason.NOT_FOUND));
            return new State(
                    row.version(), Set.of(row.video().videoAssetId()), row.video().videoAssetId());
        }
        var row =
                music.findSnapshotById(target.id())
                        .orElseThrow(() -> new MediaDeletionException(Reason.NOT_FOUND));
        var track = row.track();
        Set<UUID> ids = new HashSet<>();
        UUID selected = null;
        if (target.kind() == MediaDeletionTarget.Kind.MUSIC_TRACK) {
            for (UUID id :
                    new UUID[] {track.audioAssetId(), track.coverAssetId(), track.lyricsAssetId()})
                if (id != null) ids.add(id);
        } else {
            selected =
                    switch (target.slot()) {
                        case AUDIO -> track.audioAssetId();
                        case COVER -> track.coverAssetId();
                        case LYRICS -> track.lyricsAssetId();
                    };
            if (selected != null) ids.add(selected);
        }
        return new State(row.version(), Set.copyOf(ids), selected);
    }

    private void requireVersion(State state, MediaDeletionCommand command) {
        if (state.version() != command.expectedVersion()
                || command.expectedVersion() == Long.MAX_VALUE
                || !state.assetIds().equals(command.assetVersions().keySet()))
            throw new MediaDeletionException(Reason.CONFLICT);
    }

    private MediaDeletionOperation replay(MediaDeletionOperation prior, String fingerprint) {
        if (!prior.fingerprint().equals(fingerprint))
            throw new MediaDeletionException(Reason.CONFLICT);
        return prior;
    }

    private void requireRead(long actorId) {
        if (actorId <= 0) throw new MediaDeletionException(Reason.INVALID_REQUEST);
        if (!schemaReady.getAsBoolean()) throw new MediaDeletionException(Reason.UNAVAILABLE);
    }

    private void requireWrite(long actorId) {
        requireRead(actorId);
        if (!enabled) throw new MediaDeletionException(Reason.OPERATION_NOT_READY);
    }

    private String fingerprint(MediaDeletionCommand command) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeUTF(command.target().kind().name());
            out.writeUTF(command.target().id());
            out.writeUTF(command.target().slot() == null ? "" : command.target().slot().name());
            out.writeLong(command.expectedVersion());
            for (var entry :
                    command.assetVersions().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .toList()) {
                out.writeUTF(entry.getKey().toString());
                out.writeLong(entry.getValue());
            }
            out.flush();
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("指令指纹生成失败");
        }
    }

    private MediaDeletionException safe(RuntimeException error) {
        if (error instanceof MediaDeletionException domain) return domain;
        if (error instanceof MusicCatalogException music)
            return new MediaDeletionException(
                    switch (music.reason()) {
                        case NOT_FOUND -> Reason.NOT_FOUND;
                        case CONFLICT, DUPLICATE -> Reason.CONFLICT;
                        default -> Reason.UNAVAILABLE;
                    });
        if (error instanceof SurpriseCatalogException video)
            return new MediaDeletionException(
                    switch (video.reason()) {
                        case NOT_FOUND -> Reason.NOT_FOUND;
                        case CONFLICT -> Reason.CONFLICT;
                        default -> Reason.UNAVAILABLE;
                    });
        return new MediaDeletionException(Reason.UNAVAILABLE);
    }
}
