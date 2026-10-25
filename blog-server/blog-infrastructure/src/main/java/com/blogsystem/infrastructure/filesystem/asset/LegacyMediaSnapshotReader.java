package com.blogsystem.infrastructure.filesystem.asset;

import com.blogsystem.domain.asset.inventory.model.query.MediaCatalogSnapshot;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.domain.asset.inventory.repository.MediaSnapshotRepository;
import com.blogsystem.domain.music.model.MusicTrack;
import com.blogsystem.domain.surprise.model.SurpriseVideo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/** Reads explicit legacy snapshots, never invoking their write-capable repositories. */
public final class LegacyMediaSnapshotReader implements MediaSnapshotRepository {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RECORDS = 2000;
    private final ObjectMapper json;

    public LegacyMediaSnapshotReader(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public MediaCatalogSnapshot readMusic(Path path) {
        try {
            byte[] bytes = read(path);
            List<MusicTrack> tracks = json.readValue(bytes, new TypeReference<>() {});
            validate(tracks, MusicTrack::id);
            return snapshot(
                    "music_track",
                    path,
                    bytes,
                    tracks.size(),
                    MusicCatalogMediaReferenceSource.project(tracks));
        } catch (IOException failure) {
            throw new MediaInventoryException("媒体快照不可读");
        }
    }

    @Override
    public MediaCatalogSnapshot readSurprise(Path path) {
        if (!Files.exists(path))
            return new MediaCatalogSnapshot(
                    "surprise_video",
                    path.toAbsolutePath().normalize().toString(),
                    false,
                    "",
                    0,
                    List.of());
        try {
            byte[] bytes = read(path);
            List<SurpriseVideo> videos = json.readValue(bytes, new TypeReference<>() {});
            validate(videos, SurpriseVideo::id);
            return snapshot(
                    "surprise_video",
                    path,
                    bytes,
                    videos.size(),
                    SurpriseVideoMediaReferenceSource.project(videos));
        } catch (IOException failure) {
            throw new MediaInventoryException("媒体快照不可读");
        }
    }

    private static byte[] read(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path))
            throw new MediaInventoryException("媒体快照不存在或不可读");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new MediaInventoryException("媒体快照超过大小限制");
            return bytes;
        }
    }

    private static <T> void validate(List<T> records, Function<T, String> identity) {
        if (records == null || records.size() > MAX_RECORDS)
            throw new MediaInventoryException("媒体快照结构或数量无效");
        var ids = new HashSet<String>();
        for (T record : records) {
            if (record == null || !ids.add(identity.apply(record)))
                throw new MediaInventoryException("媒体快照包含空记录或重复业务 ID");
        }
    }

    private static MediaCatalogSnapshot snapshot(
            String source, Path path, byte[] bytes, int count, List<MediaReference> references) {
        try {
            String checksum =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new MediaCatalogSnapshot(
                    source,
                    path.toAbsolutePath().normalize().toString(),
                    true,
                    checksum,
                    count,
                    references);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
