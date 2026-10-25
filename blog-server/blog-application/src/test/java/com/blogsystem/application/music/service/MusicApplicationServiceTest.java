package com.blogsystem.application.music.service;

import com.blogsystem.domain.music.gateway.MusicAccountGateway;
import com.blogsystem.domain.music.model.MusicAccount;
import com.blogsystem.domain.music.model.MusicException;
import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;


import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicApplicationServiceTest {
    MusicAccountGateway accounts = mock(MusicAccountGateway.class);
    PlaylistSnapshotRepository snapshots = mock(PlaylistSnapshotRepository.class);
    MusicApplicationService service = new MusicApplicationService(accounts, snapshots, new MusicPolicy("939817038"));

    MusicPlaylist playlist(String id) {
        return new MusicPlaylist(id, "博客歌单", Instant.parse("2026-10-03T00:00:00Z"), List.of(new MusicPlaylist.Song("1", "歌曲", "歌手", "", true, true)));
    }

    @Test
    void syncUsesConfiguredResourceAndCommitsOnlyAfterSuccessfulRead() {
        var result = playlist("939817038");
        when(accounts.synchronize("939817038")).thenReturn(result);
        assertEquals(result, service.synchronize());
        verify(snapshots).save(result);
    }

    @Test
    void failedOrDifferentResourceSyncDoesNotReplaceSnapshot() {
        when(accounts.synchronize("939817038")).thenThrow(new MusicException(MusicException.Reason.UNAVAILABLE));
        assertThrows(MusicException.class, service::synchronize);
        verifyNoInteractions(snapshots);
        doReturn(playlist("2")).when(accounts).synchronize("939817038");
        assertThrows(MusicException.class, service::synchronize);
        verifyNoInteractions(snapshots);
    }

    @Test
    void visitorReadDoesNotCallOfficialAccountAndStripsOwnerRights() {
        when(snapshots.find()).thenReturn(Optional.of(playlist("939817038")));
        var value = service.publicPlaylist().orElseThrow();
        assertEquals("1", value.songs().get(0).id());
        assertEquals(4, MusicApplicationService.PublicSong.class.getRecordComponents().length);
        verifyNoInteractions(accounts);
    }

    @Test
    void snapshotsForAnotherConfiguredPlaylistAreNotPublished() {
        when(snapshots.find()).thenReturn(Optional.of(playlist("2")));
        assertTrue(service.publicPlaylist().isEmpty());
        verifyNoInteractions(accounts);
    }

    @Test
    void revocationClearsPublishedSnapshotEvenWhenRemoteLogoutIsUnconfirmed() {
        when(accounts.revoke()).thenReturn(new MusicAccount.Revocation(false, false));
        assertFalse(service.revoke().remoteLogoutConfirmed());
        verify(snapshots).clear();
    }

    @Test
    void policyRejectsCommandInjectionOrArbitraryResources() {
        for (String id : List.of("--help", "1;logout", "https://example.test", "0"))
            assertThrows(IllegalArgumentException.class, () -> new MusicPolicy(id));
    }
}
