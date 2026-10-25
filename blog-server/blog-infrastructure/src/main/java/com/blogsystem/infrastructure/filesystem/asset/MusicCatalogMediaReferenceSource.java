package com.blogsystem.infrastructure.filesystem.asset;

import com.blogsystem.domain.asset.inventory.gateway.MediaReferenceSource;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.music.model.MusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicCatalogRepository;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Projects legacy music-catalog references without changing the catalog. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "media.catalog.storage",
        havingValue = "legacy-json",
        matchIfMissing = true)
public final class MusicCatalogMediaReferenceSource implements MediaReferenceSource {
    private final MusicCatalogRepository catalog;

    public MusicCatalogMediaReferenceSource(MusicCatalogRepository catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<MediaReference> list() {
        Optional<List<MusicTrack>> stored = catalog.find();
        if (stored == null) throw new MediaInventoryException("音乐目录返回空结果");
        return project(stored.orElseGet(List::of));
    }

    public static List<MediaReference> project(List<MusicTrack> tracks) {
        if (tracks == null) throw new MediaInventoryException("音乐目录返回空列表");
        List<MediaReference> references = new ArrayList<>();
        for (MusicTrack track : tracks) {
            if (track == null) {
                throw new MediaInventoryException("音乐目录包含空歌曲");
            }
            add(references, track, MediaKind.AUDIO, track.audioPath(), "音频");
            add(references, track, MediaKind.IMAGE, track.coverPath(), "封面");
            add(references, track, MediaKind.LYRICS, track.lyricsPath(), "歌词");
        }
        return List.copyOf(references);
    }

    private static void add(
            List<MediaReference> references,
            MusicTrack track,
            MediaKind kind,
            String path,
            String role) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            references.add(
                    new MediaReference(
                            kind,
                            new MediaObjectKey(path),
                            "music_track",
                            track.id(),
                            track.title() + " · " + role));
        } catch (IllegalArgumentException error) {
            throw new MediaInventoryException("音乐目录包含不安全的媒体路径", error);
        }
    }
}
