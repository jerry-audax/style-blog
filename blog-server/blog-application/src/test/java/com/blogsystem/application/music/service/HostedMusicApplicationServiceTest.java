package com.blogsystem.application.music.service;

import com.blogsystem.domain.music.gateway.HostedAudioGateway;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.model.HostedAudio;
import com.blogsystem.domain.music.model.HostedMusicUnavailable;
import com.blogsystem.domain.music.model.MusicException;
import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostedMusicApplicationServiceTest {
    @Test
    void readsImportedAudioWithoutRequiringOrInventingAnOfficialSongId() {
        var audio = mock(HostedAudioGateway.class);
        when(audio.findPublished()).thenReturn(List.of(new HostedAudio("blog/music/a.m4a", "歌曲", "https://example.test/file/blog/music/a.m4a", "audio/x-m4a", 100)));
        var playlist = service(audio).playlist();
        assertEquals("imgbed", playlist.source());
        assertEquals("blog/music/a.m4a", playlist.songs().get(0).id());
        assertEquals("", playlist.songs().get(0).artist());
        assertEquals("", playlist.songs().get(0).cover());
        verify(audio).findPublished();
        verifyNoMoreInteractions(audio);
    }

    @Test
    void emptyDirectoryIsAnEmptyPlaylistAndFailureIsNotACommunityFallback() {
        var audio = mock(HostedAudioGateway.class);
        when(audio.findPublished()).thenReturn(List.of());
        var service = service(audio);
        assertTrue(service.playlist().songs().isEmpty());
        when(audio.findPublished()).thenThrow(new HostedMusicUnavailable());
        assertThrows(HostedMusicUnavailable.class, service::playlist);
    }

    private HostedMusicApplicationService service(HostedAudioGateway audio) {
        return new HostedMusicApplicationService(audio, mock(PlaylistSnapshotRepository.class),
                mock(SongArtworkGateway.class), new MusicPolicy("939817038"));
    }

    @Test
    void uniquePlaylistMatchAddsArtistAndCoverButNeverChangesTheAudio() {
        var audio = mock(HostedAudioGateway.class);
        var snapshots = mock(PlaylistSnapshotRepository.class);
        var artwork = mock(SongArtworkGateway.class);
        var a = new HostedAudio("blog/music/a.m4a", "  BLANK SPACE ", "https://example.test/file/a.m4a", "audio/mp4", 100);
        when(audio.findPublished()).thenReturn(List.of(a));
        when(snapshots.find()).thenReturn(java.util.Optional.of(new MusicPlaylist("939817038", "歌单", java.time.Instant.now(),
                List.of(new MusicPlaylist.Song("29572501", "Blank Space", "Taylor Swift", "", true, false)))));
        when(artwork.findCovers(java.util.Set.of("29572501"))).thenReturn(java.util.Map.of("29572501", "https://p1.music.126.net/a.jpg"));
        var result = new HostedMusicApplicationService(audio, snapshots, artwork, new MusicPolicy("939817038")).playlist().songs().get(0);
        assertEquals("Taylor Swift", result.artist());
        assertEquals("https://p1.music.126.net/a.jpg", result.cover());
        assertEquals(a.url(), result.url());
        assertEquals(a.id(), result.id());
    }

    @Test
    void duplicateTitlesAndMissingOptionalMetadataDoNotInventArtistOrArtwork() {
        var a = new HostedAudio("a", "Song", "https://example.test/file/a.mp3", "audio/mp3", 1);
        var audio = mock(HostedAudioGateway.class);
        var snapshots = mock(PlaylistSnapshotRepository.class);
        var artwork = mock(SongArtworkGateway.class);
        when(audio.findPublished()).thenReturn(List.of(a));
        when(snapshots.find()).thenReturn(java.util.Optional.of(new MusicPlaylist("939817038", "歌单", java.time.Instant.now(),
                List.of(new MusicPlaylist.Song("1", "Song", "A", "", false, false), new MusicPlaylist.Song("2", "Song", "B", "", false, false)))));
        var service = new HostedMusicApplicationService(audio, snapshots, artwork, new MusicPolicy("939817038"));
        assertEquals("", service.playlist().songs().get(0).artist());
        verifyNoInteractions(artwork);
        when(snapshots.find()).thenThrow(new MusicException(MusicException.Reason.UNAVAILABLE));
        assertEquals("", service.playlist().songs().get(0).cover());
    }
}
