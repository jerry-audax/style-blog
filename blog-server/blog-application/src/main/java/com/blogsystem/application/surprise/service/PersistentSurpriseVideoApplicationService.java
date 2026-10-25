package com.blogsystem.application.surprise.service;

import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.CONFLICT;
import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.INVALID_REQUEST;
import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.MISSING_VERSION;
import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.NOT_FOUND;
import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.OPERATION_NOT_READY;
import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.UNAVAILABLE;

import com.blogsystem.application.asset.metadata.result.MediaAssetProjection;
import com.blogsystem.application.asset.metadata.service.MediaAssetQueryApplicationService;
import com.blogsystem.application.surprise.port.SurpriseVideoUseCase;
import com.blogsystem.application.surprise.query.SurpriseReferenceQuery;
import com.blogsystem.application.surprise.result.SurpriseVideoAdminView;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.surprise.model.SurpriseCatalogException;
import com.blogsystem.domain.surprise.model.SurpriseVideoMetadata;
import com.blogsystem.domain.surprise.model.SurpriseVideoSnapshot;
import com.blogsystem.domain.surprise.model.SurpriseVideoUpload;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoCatalogRepository;

import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class PersistentSurpriseVideoApplicationService
        implements SurpriseVideoUseCase, SurpriseReferenceQuery {
    private final SurpriseVideoCatalogRepository videos;
    private final MediaAssetQueryApplicationService assets;
    private final Clock clock;
    private final TransactionOperations transactions;

    public PersistentSurpriseVideoApplicationService(
            SurpriseVideoCatalogRepository videos,
            MediaAssetQueryApplicationService assets,
            Clock clock,
            TransactionOperations transactions) {
        this.videos = Objects.requireNonNull(videos);
        this.assets = Objects.requireNonNull(assets);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public List<SurpriseVideoAdminView> adminCatalog() {
        return read(
                () -> {
                    var rows = videos.findAdminList();
                    var media = assets.findByIds(assetIds(rows));
                    return rows.stream()
                            .map(row -> view(row, requireAsset(media, row.video().videoAssetId())))
                            .toList();
                });
    }

    @Override
    public SurpriseVideoApplicationService.VideoView randomPublic() {
        return read(
                () -> {
                    var rows = videos.findPublicCandidates();
                    var media = assets.findByIds(assetIds(rows));
                    var eligible =
                            rows.stream()
                                    .filter(
                                            row -> {
                                                var a = media.get(row.video().videoAssetId());
                                                return row.video().enabled()
                                                        && a != null
                                                        && a.kind() == MediaKind.VIDEO
                                                        && a.status() == MediaAssetStatus.ACTIVE;
                                            })
                                    .toList();
                    if (eligible.isEmpty()) throw new SurpriseCatalogException(UNAVAILABLE);
                    var row = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
                    var a = media.get(row.video().videoAssetId());
                    var v = row.video();
                    return new SurpriseVideoApplicationService.VideoView(
                            v.id(),
                            v.title(),
                            a.url(),
                            a.objectKey(),
                            a.mimeType() == null ? "" : a.mimeType(),
                            a.size() == null ? 0 : a.size(),
                            v.enabled(),
                            v.sortOrder());
                });
    }

    @Override
    public SurpriseVideoAdminView updateMetadata(
            String id, String title, Boolean enabled, Integer sortOrder, Long expectedVersion) {
        if (expectedVersion == null) throw new SurpriseCatalogException(MISSING_VERSION);
        if (expectedVersion < 0) throw new SurpriseCatalogException(INVALID_REQUEST);
        var current =
                read(
                        () ->
                                videos.findSnapshotById(id)
                                        .orElseThrow(
                                                () -> new SurpriseCatalogException(NOT_FOUND)));
        if (current.version() != expectedVersion) throw new SurpriseCatalogException(CONFLICT);
        var v = current.video();
        SurpriseVideoMetadata metadata;
        try {
            metadata =
                    new SurpriseVideoMetadata(
                            title == null ? v.title() : title,
                            enabled == null ? v.enabled() : enabled,
                            sortOrder == null ? v.sortOrder() : sortOrder);
        } catch (IllegalArgumentException invalid) {
            throw new SurpriseCatalogException(INVALID_REQUEST);
        }
        var prepared =
                read(
                        () ->
                                requireAsset(
                                        assets.findByIds(Set.of(v.videoAssetId())),
                                        v.videoAssetId()));
        var saved =
                read(
                        () ->
                                transactions.execute(
                                        status ->
                                                videos.updateMetadata(
                                                        id,
                                                        metadata,
                                                        expectedVersion,
                                                        clock.instant())));
        if (saved == null) throw new SurpriseCatalogException(UNAVAILABLE);
        return view(saved, prepared);
    }

    @Override
    public SurpriseVideoApplicationService.VideoView create(
            String title, boolean enabled, int sortOrder, SurpriseVideoUpload upload) {
        throw new SurpriseCatalogException(OPERATION_NOT_READY);
    }

    @Override
    public void delete(String id) {
        throw new SurpriseCatalogException(OPERATION_NOT_READY);
    }

    @Override
    public List<MediaReference> listMediaReferences() {
        return read(
                () -> {
                    var rows = videos.findAdminList();
                    var media = assets.findByIds(assetIds(rows));
                    return rows.stream()
                            .map(
                                    row ->
                                            new MediaReference(
                                                    MediaKind.VIDEO,
                                                    new MediaObjectKey(
                                                            requireAsset(
                                                                            media,
                                                                            row.video()
                                                                                    .videoAssetId())
                                                                    .objectKey()),
                                                    "surprise_video",
                                                    row.video().id(),
                                                    row.video().title()))
                            .toList();
                });
    }

    private static Set<UUID> assetIds(List<SurpriseVideoSnapshot> rows) {
        Set<UUID> ids = new HashSet<>();
        for (var row : rows) ids.add(row.video().videoAssetId());
        return Set.copyOf(ids);
    }

    private static MediaAssetProjection requireAsset(
            Map<UUID, MediaAssetProjection> media, UUID id) {
        var a = media.get(id);
        if (a == null) throw new SurpriseCatalogException(UNAVAILABLE);
        return a;
    }

    private static SurpriseVideoAdminView view(SurpriseVideoSnapshot row, MediaAssetProjection a) {
        var v = row.video();
        return new SurpriseVideoAdminView(
                v.id(),
                v.title(),
                a.url(),
                a.objectKey(),
                a.mimeType(),
                a.size(),
                v.enabled(),
                v.sortOrder(),
                row.version());
    }

    private static <T> T read(Supplier<T> query) {
        try {
            return query.get();
        } catch (SurpriseCatalogException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new SurpriseCatalogException(UNAVAILABLE);
        }
    }
}
