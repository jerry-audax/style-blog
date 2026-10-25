package com.blogsystem.infrastructure.filesystem.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.domain.music.model.MusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicCatalogRepository;
import com.blogsystem.domain.surprise.model.SurpriseVideo;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaReferenceSourceAdapterTest {
    @Test
    void musicSourceProjectsOnlyConfiguredMediaPaths() {
        MusicCatalogRepository catalog = new MusicCatalogRepository() {
            @Override
            public Optional<List<MusicTrack>> find() {
                return Optional.of(List.of(new MusicTrack(
                        "track-1", "示例歌曲", "歌手", "source-1",
                        "blog/music/track.mp3", "blog/covers/track.jpg", "", true, 0)));
            }

            @Override
            public void save(List<MusicTrack> tracks) {
                throw new AssertionError("只读盘点不能写入音乐目录");
            }
        };

        var references = new MusicCatalogMediaReferenceSource(catalog).list();

        assertEquals(2, references.size());
        assertEquals(MediaKind.AUDIO, references.get(0).kind());
        assertEquals("blog/music/track.mp3", references.get(0).objectKey().value());
        assertEquals(MediaKind.IMAGE, references.get(1).kind());
        assertEquals("blog/covers/track.jpg", references.get(1).objectKey().value());
    }

    @Test
    void surpriseSourceProjectsEveryVideoIncludingDisabledOnes() {
        SurpriseVideoRepository videos = new SurpriseVideoRepository() {
            @Override public List<SurpriseVideo> findAll() {
                return List.of(new SurpriseVideo(
                        "video-1", "惊喜", "https://example.test/video.mp4",
                        "blog/surprise/video.mp4", "video/mp4", 12L, false, 0));
            }
            @Override public Optional<SurpriseVideo> findById(String id) { return Optional.empty(); }
            @Override public SurpriseVideo save(SurpriseVideo video) { throw new AssertionError("只读盘点不能写入视频目录"); }
            @Override public void delete(String id) { throw new AssertionError("只读盘点不能删除视频目录"); }
        };

        var references = new SurpriseVideoMediaReferenceSource(videos).list();

        assertEquals(1, references.size());
        assertEquals(MediaKind.VIDEO, references.get(0).kind());
        assertEquals("video-1", references.get(0).ownerId());
        assertEquals("blog/surprise/video.mp4", references.get(0).objectKey().value());
    }

    @Test
    void sourceRejectsExternalOrMalformedObjectPaths() {
        SurpriseVideoRepository videos = new SurpriseVideoRepository() {
            @Override public List<SurpriseVideo> findAll() {
                return List.of(new SurpriseVideo(
                        "video-1", "外链", "https://example.test/video.mp4",
                        "https://example.test/video.mp4", "video/mp4", 12L, true, 0));
            }
            @Override public Optional<SurpriseVideo> findById(String id) { return Optional.empty(); }
            @Override public SurpriseVideo save(SurpriseVideo video) { throw new AssertionError(); }
            @Override public void delete(String id) { throw new AssertionError(); }
        };

        assertThrows(MediaInventoryException.class,
                () -> new SurpriseVideoMediaReferenceSource(videos).list());
    }
}
