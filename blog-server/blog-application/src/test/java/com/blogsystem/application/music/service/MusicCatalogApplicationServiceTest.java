package com.blogsystem.application.music.service;

import com.blogsystem.domain.music.gateway.HostedAudioGateway;
import com.blogsystem.domain.music.gateway.MusicMediaGateway;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.model.HostedAudio;
import com.blogsystem.domain.music.model.MusicMedia;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicMediaUpload;
import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.domain.music.model.MusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicCatalogRepository;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;

import java.util.HashSet;
import java.util.Optional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

class MusicCatalogApplicationServiceTest {
    @Test
    void firstReadImportsExistingHostedAudioAndLaterEditsStayLocal() {
        var audio = new FakeAudio(List.of(new HostedAudio("blog/music/one.mp3", "One", "https://img.test/file/blog/music/one.mp3", "audio/mpeg", 10)));
        var catalog = new FakeCatalog();
        var media = new FakeMedia();
        var service = new MusicCatalogApplicationService(audio, catalog, media, OptionalRepository.empty(), ids -> Map.of(), new MusicPolicy("939817038"));

        var imported = service.catalog();
        assertEquals("One", imported.get(0).title());
        assertEquals("blog/music/one.mp3", imported.get(0).audio().path());
        var edited = service.saveMetadata(imported.get(0).id(), "自定义标题", "博主", "", true, 2);
        assertEquals("自定义标题", edited.title());
        assertEquals("博主", edited.artist());
    }

    @Test
    void mediaCanBeReplacedOrRemovedWithoutChangingTrackMetadata() {
        var audio = new FakeAudio(List.of(new HostedAudio("blog/music/one.mp3", "One", "https://img.test/one.mp3", "audio/mpeg", 10)));
        var catalog = new FakeCatalog();
        var media = new FakeMedia();
        var service = new MusicCatalogApplicationService(audio, catalog, media, OptionalRepository.empty(), ids -> Map.of(), new MusicPolicy("939817038"));
        var track = service.catalog().get(0);
        var uploaded = service.upload(track.id(), new MusicMediaUpload(new byte[]{1, 2}, "audio/mpeg", "new.mp3", MusicMediaKind.AUDIO));
        assertEquals("blog/music/new.mp3", uploaded.audio().path());
        assertTrue(media.deleted.contains("blog/music/one.mp3"));
        var removed = service.deleteMedia(track.id(), MusicMediaKind.AUDIO);
        assertNull(removed.audio());
        assertTrue(media.deleted.contains("blog/music/new.mp3"));
    }

    @Test
    void disabledTracksAreNotPublished() {
        var audio = new FakeAudio(List.of(new HostedAudio("blog/music/one.mp3", "One", "https://img.test/one.mp3", "audio/mpeg", 10)));
        var catalog = new FakeCatalog();
        var service = new MusicCatalogApplicationService(audio, catalog, new FakeMedia(), OptionalRepository.empty(), ids -> Map.of(), new MusicPolicy("939817038"));
        var track = service.catalog().get(0);
        service.saveMetadata(track.id(), track.title(), track.artist(), "", false, 0);
        assertTrue(service.publicPlaylist().songs().isEmpty());
    }

    @Test
    void deletingTrackRemovesEveryHostedMediaBeforeRemovingCatalogEntry() {
        var audio = new FakeAudio(List.of());
        var catalog = new FakeCatalog();
        catalog.value = new ArrayList<>(List.of(new MusicTrack("track-1", "One", "Artist", "source-1",
                "blog/music/one.mp3", "blog/music/covers/one.jpg", "blog/music/lyrics/one.lrc", true, 0)));
        var media = new FakeMedia();
        var service = new MusicCatalogApplicationService(audio, catalog, media, OptionalRepository.empty(), ids -> Map.of(), new MusicPolicy("939817038"));

        service.deleteTrack("track-1");

        assertTrue(catalog.value.isEmpty());
        assertEquals(Set.of("blog/music/one.mp3", "blog/music/covers/one.jpg", "blog/music/lyrics/one.lrc"), media.deleted);
    }

    private static final class FakeAudio implements HostedAudioGateway {
        private final List<HostedAudio> values;
        private FakeAudio(List<HostedAudio> values) { this.values = values; }
        public List<HostedAudio> findPublished() { return values; }
    }

    private static final class FakeCatalog implements MusicCatalogRepository {
        private List<MusicTrack> value;
        public Optional<List<MusicTrack>> find() { return Optional.ofNullable(value); }
        public void save(List<MusicTrack> tracks) { value = new ArrayList<>(tracks); }
    }

    private static final class FakeMedia implements MusicMediaGateway {
        private final Set<String> deleted = new HashSet<>();
        public MusicMedia upload(MusicMediaUpload upload) {
            return new MusicMedia("blog/music/new.mp3", "https://img.test/file/blog/music/new.mp3", upload.mediaType(), upload.bytes().length);
        }
        public void delete(String path) { deleted.add(path); }
        public String publicUrl(String path) { return "https://img.test/file/" + path; }
    }

    private static final class OptionalRepository implements PlaylistSnapshotRepository, SongArtworkGateway {
        static OptionalRepository empty() { return new OptionalRepository(); }
        public Optional<MusicPlaylist> find() { return Optional.empty(); }
        public void save(MusicPlaylist value) { }
        public void clear() { }
        public Map<String, String> findCovers(Set<String> ids) { return Map.of(); }
    }
}
