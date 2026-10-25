package com.blogsystem.application.asset.inventory.service;

import static com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure.Outcome.COMMIT_UNKNOWN;
import static com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED;

import com.blogsystem.domain.asset.inventory.model.query.MediaImportReceipt;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportSources;
import com.blogsystem.domain.asset.inventory.repository.MediaImportInputRepository;
import com.blogsystem.domain.asset.inventory.repository.MediaImportSchemaRepository;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicTrackMetadataRepository;
import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoMetadataRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;

import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** DDL, asset registration, catalogue references and receipt preparation are one offline transaction. */
public final class MediaMetadataImportApplicationService {
    private final MediaImportInputRepository inputs;
    private final MediaImportSchemaRepository schema;
    private final MediaAssetImportApplicationService assets;
    private final MusicTrackMetadataRepository music;
    private final SurpriseVideoMetadataRepository videos;
    private final MediaReferenceWriteRepository references;
    private final TransactionOperations transactions;
    private final Clock clock;

    public MediaMetadataImportApplicationService(
            MediaImportInputRepository inputs,
            MediaImportSchemaRepository schema,
            MediaAssetRepository assets,
            MusicTrackMetadataRepository music,
            SurpriseVideoMetadataRepository videos,
            MediaReferenceWriteRepository references,
            TransactionOperations transactions,
            Clock clock) {
        this.inputs = inputs;
        this.schema = schema;
        this.assets = new MediaAssetImportApplicationService(assets);
        this.music = music;
        this.videos = videos;
        this.references = Objects.requireNonNull(references);
        this.transactions = transactions;
        this.clock = clock;
    }

    public String execute(
            MediaImportSources sources,
            Function<MediaImportReceipt, String> serializeBeforeCommit) {
        var input = inputs.read(sources); // File validation completes before acquiring a database
        // connection.
        var commitStarted = new AtomicBoolean();
        String[] stage = {"DATABASE_GUARD"};
        try {
            return Objects.requireNonNull(
                    transactions.execute(
                            status -> {
                                boolean initialized =
                                        schema.prepare(
                                                sources.database(),
                                                sources.schema(),
                                                sources.ddl());
                                stage[0] = "ASSETS";
                                var now = clock.instant();
                                var registered =
                                        assets.registerExisting(
                                                input.provider(),
                                                input.root(),
                                                input.objects(),
                                                now);
                                Map<String, MediaAsset> index = new HashMap<>();
                                registered
                                        .assets()
                                        .forEach(
                                                asset ->
                                                        index.put(
                                                                asset.objectKey().value(), asset));
                                int tracksCreated = 0,
                                        videosCreated = 0,
                                        missingBindings = 0,
                                        skippedVideos = 0;
                                var newTracks = new ArrayList<PersistedMusicTrack>();
                                var newVideos = new ArrayList<PersistedSurpriseVideo>();
                                var desiredIds = new HashSet<UUID>();
                                var desiredLinks = new ArrayList<String>();
                                stage[0] = "MUSIC";
                                for (var track : input.tracks()) {
                                    UUID audio =
                                            resolve(
                                                    index,
                                                    track.audioPath(),
                                                    MediaKind.AUDIO,
                                                    input.root());
                                    UUID cover =
                                            resolve(
                                                    index,
                                                    track.coverPath(),
                                                    MediaKind.IMAGE,
                                                    input.root());
                                    UUID lyrics =
                                            resolve(
                                                    index,
                                                    track.lyricsPath(),
                                                    MediaKind.LYRICS,
                                                    input.root());
                                    if (!track.audioPath().isBlank() && audio == null)
                                        missingBindings++;
                                    if (!track.coverPath().isBlank() && cover == null)
                                        missingBindings++;
                                    if (!track.lyricsPath().isBlank() && lyrics == null)
                                        missingBindings++;
                                    var record =
                                            new PersistedMusicTrack(
                                                    track.id(),
                                                    track.title(),
                                                    track.artist(),
                                                    blankToNull(track.sourceId()),
                                                    audio,
                                                    cover,
                                                    lyrics,
                                                    input.artwork().get(track.sourceId()),
                                                    track.enabled(),
                                                    track.sortOrder());
                                    if (music.findById(track.id()).isEmpty()) {
                                        newTracks.add(record);
                                        if (audio != null) desiredIds.add(audio);
                                        if (cover != null) desiredIds.add(cover);
                                        if (lyrics != null) desiredIds.add(lyrics);
                                        if (record.externalCoverUrl() != null && !record.externalCoverUrl().isBlank())
                                            desiredLinks.add(record.externalCoverUrl());
                                    }
                                }
                                stage[0] = "VIDEO";
                                for (var video : input.videos()) {
                                    UUID resource =
                                            resolve(
                                                    index,
                                                    video.path(),
                                                    MediaKind.VIDEO,
                                                    input.root());
                                    if (resource == null) {
                                        skippedVideos++;
                                        missingBindings++;
                                        continue;
                                    }
                                    if (videos.findById(video.id()).isEmpty()) {
                                        newVideos.add(new PersistedSurpriseVideo(
                                                    video.id(),
                                                    video.title(),
                                                    resource,
                                                    video.enabled(),
                                                    video.sortOrder()));
                                        desiredIds.add(resource);
                                    }
                                }
                                // 全批保护必须早于首条业务INSERT，不能持有歌曲锁后再追加下一首资源锁。
                                stage[0] = "REFERENCE_GUARD";
                                references.lockForWrite(MediaReferenceWriteIntent.empty(),
                                        new MediaReferenceWriteIntent(desiredLinks, List.of(), desiredIds));
                                stage[0] = "MUSIC";
                                for (var track : newTracks) {
                                    if (music.insertIfAbsent(track, now)) tracksCreated++;
                                    if (music.findById(track.id()).isEmpty())
                                        throw new IllegalStateException("Imported track unavailable");
                                }
                                stage[0] = "VIDEO";
                                for (var video : newVideos) {
                                    if (videos.insertIfAbsent(video, now)) videosCreated++;
                                    if (videos.findById(video.id()).isEmpty())
                                        throw new IllegalStateException(
                                                "Imported video unavailable");
                                }
                                var receipt =
                                        new MediaImportReceipt(
                                                "ADDITIVE_METADATA_IMPORT",
                                                sources.database(),
                                                sources.schema(),
                                                initialized ? 1 : 0,
                                                registered.created(),
                                                registered.preserved(),
                                                tracksCreated,
                                                input.tracks().size() - tracksCreated,
                                                videosCreated,
                                                input.videos().size()
                                                        - skippedVideos
                                                        - videosCreated,
                                                skippedVideos,
                                                missingBindings,
                                                input.videoSnapshotMissing(),
                                                false);
                                String serialized =
                                        Objects.requireNonNull(
                                                serializeBeforeCommit.apply(receipt));
                                stage[0] = "COMMIT";
                                commitStarted.set(true);
                                return serialized;
                            }));
        } catch (RuntimeException failure) {
            if (commitStarted.get())
                throw new MediaImportFailure(
                        "COMMIT", COMMIT_UNKNOWN, failure.getClass().getSimpleName());
            if (failure instanceof MediaImportFailure safe) throw safe;
            throw new MediaImportFailure(
                    stage[0], ROLLBACK_ATTEMPTED, failure.getClass().getSimpleName());
        }
    }

    private static UUID resolve(
            Map<String, MediaAsset> index, String path, MediaKind expected, String root) {
        if (path == null || path.isBlank()) return null;
        new MediaObjectKey(path).requireWithin(root);
        MediaAsset asset = index.get(path);
        if (asset == null) return null;
        if (asset.kind() != expected)
            throw new IllegalArgumentException("Business asset type mismatch");
        return asset.status() == MediaAssetStatus.ACTIVE ? asset.id() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
