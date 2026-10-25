package com.blogsystem.infrastructure.filesystem.asset;

import com.blogsystem.domain.asset.inventory.model.query.MediaImportBatch;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportSources;
import com.blogsystem.domain.asset.inventory.repository.MediaImportInputRepository;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.music.model.MusicTrack;
import com.blogsystem.domain.surprise.model.SurpriseVideo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Validated offline import input. No database/provider calls occur while reading it. */
public final class MediaMetadataImportInputReader implements MediaImportInputRepository {
    private final ObjectMapper json;

    public MediaMetadataImportInputReader(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json);
    }

    @Override
    public MediaImportBatch read(MediaImportSources sources) {
        try {
            if ("blog_system".equals(sources.database())) {
                Path backup = sources.backup();
                if (backup == null || !Files.isRegularFile(backup))
                    throw new IllegalArgumentException();
                try (var stream = Files.newInputStream(backup)) {
                    if (!"PGDMP"
                            .equals(
                                    new String(
                                            stream.readNBytes(5),
                                            java.nio.charset.StandardCharsets.US_ASCII)))
                        throw new IllegalArgumentException();
                }
            }
            return readValidated(sources);
        } catch (Exception failure) {
            throw new MediaImportFailure(
                    "INPUT",
                    MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED,
                    failure.getClass().getSimpleName());
        }
    }

    private MediaImportBatch readValidated(MediaImportSources sources) throws Exception {
        JsonNode report = json.readTree(bytes(sources.report()));
        if (!"READ_ONLY_DRY_RUN".equals(report.path("mode").asText())
                || !"cloudflare_imgbed".equals(report.path("provider").asText()))
            throw new IllegalArgumentException("Invalid provider report");
        String root = report.path("rootFolder").asText();
        if (!"blog".equals(root)) throw new IllegalArgumentException("Unexpected namespace");
        Instant finished = Instant.parse(report.path("scanFinishedAt").asText());
        if (finished.isBefore(Instant.now().minusSeconds(86400))
                || finished.isAfter(Instant.now().plusSeconds(300)))
            throw new IllegalArgumentException("Inventory report must be fresh");
        JsonNode objectsNode = report.path("objects");
        if (!objectsNode.isArray() || objectsNode.size() > 2000)
            throw new IllegalArgumentException("Invalid object inventory");
        List<MediaInventoryObject> objects = new ArrayList<>();
        for (JsonNode object : objectsNode) {
            MediaObjectKey key = new MediaObjectKey(object.path("objectKey").asText());
            key.requireWithin(root);
            objects.add(
                    new MediaInventoryObject(
                            MediaKind.valueOf(object.path("kind").asText()),
                            key,
                            json.treeToValue(object.path("metadata"), MediaMetadata.class)));
        }
        byte[] musicBytes = bytes(sources.musicCatalog());
        verifyChecksum(report, "music_track", musicBytes);
        List<MusicTrack> tracks = json.readValue(musicBytes, new TypeReference<>() {});
        verifyRecords(tracks, MusicTrack::id);
        List<SurpriseVideo> videos = List.of();
        Path videoPath = sources.surpriseCatalog();
        if (Files.exists(videoPath)) {
            byte[] videoBytes = bytes(videoPath);
            verifyChecksum(report, "surprise_video", videoBytes);
            videos = json.readValue(videoBytes, new TypeReference<>() {});
            verifyRecords(videos, SurpriseVideo::id);
        } else {
            JsonNode snapshot = snapshot(report, "surprise_video");
            if (snapshot.path("available").asBoolean(true))
                throw new IllegalArgumentException("Expected video snapshot is missing");
        }
        Map<String, String> artwork = new HashMap<>();
        if (sources.playlist() != null) {
            JsonNode songs = json.readTree(bytes(sources.playlist())).path("songs");
            if (!songs.isArray() || songs.size() > 2000)
                throw new IllegalArgumentException("Invalid playlist snapshot");
            for (JsonNode song : songs) {
                String id = song.path("id").asText(), cover = song.path("cover").asText();
                if (!id.isBlank() && !cover.isBlank()) {
                    String previous = artwork.putIfAbsent(id, cover);
                    if (previous != null && !previous.equals(cover))
                        throw new IllegalArgumentException("Conflicting artwork snapshot");
                }
            }
        }
        // Missing shared-provider resources are allowed, but contradictory business types are not.
        Map<String, MediaKind> expectedKinds = new HashMap<>();
        for (var track : tracks) {
            expectPath(expectedKinds, track.audioPath(), MediaKind.AUDIO, root);
            expectPath(expectedKinds, track.coverPath(), MediaKind.IMAGE, root);
            expectPath(expectedKinds, track.lyricsPath(), MediaKind.LYRICS, root);
        }
        for (var video : videos) expectPath(expectedKinds, video.path(), MediaKind.VIDEO, root);
        return new MediaImportBatch(
                "cloudflare_imgbed",
                root,
                List.copyOf(objects),
                List.copyOf(tracks),
                List.copyOf(videos),
                Map.copyOf(artwork),
                !Files.exists(sources.surpriseCatalog()));
    }

    private static void expectPath(
            Map<String, MediaKind> expected, String path, MediaKind kind, String root) {
        if (path == null || path.isBlank()) return;
        new MediaObjectKey(path).requireWithin(root);
        MediaKind previous = expected.putIfAbsent(path, kind);
        if (previous != null && previous != kind)
            throw new IllegalArgumentException("Conflicting business resource types");
    }

    private static byte[] bytes(Path path) throws Exception {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Snapshot is missing");
        try (var stream = Files.newInputStream(path)) {
            byte[] data = stream.readNBytes(4 * 1024 * 1024 + 1);
            if (data.length > 4 * 1024 * 1024)
                throw new IllegalArgumentException("Snapshot is too large");
            return data;
        }
    }

    private static JsonNode snapshot(JsonNode report, String source) {
        JsonNode found = null;
        for (JsonNode item : report.path("snapshots")) {
            if (source.equals(item.path("source").asText())) {
                if (found != null) throw new IllegalArgumentException("Duplicate snapshot source");
                found = item;
            }
        }
        if (found == null) throw new IllegalArgumentException("Missing snapshot evidence");
        return found;
    }

    private static void verifyChecksum(JsonNode report, String source, byte[] data)
            throws Exception {
        JsonNode evidence = snapshot(report, source);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        if (!evidence.path("available").asBoolean()
                || !sha.equals(evidence.path("sha256").asText()))
            throw new IllegalArgumentException("Snapshot changed after inventory");
    }

    private static <T> void verifyRecords(List<T> records, Function<T, String> id) {
        if (records == null || records.size() > 2000)
            throw new IllegalArgumentException("Invalid catalog");
        var identities = new HashSet<String>();
        for (T value : records)
            if (value == null || !identities.add(id.apply(value)))
                throw new IllegalArgumentException("Duplicate/null catalog identity");
    }
}
