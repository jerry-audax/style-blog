package com.blogsystem.app.config.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.domain.music.model.*;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.record.music.MusicTrackRecord;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MusicCoverBackfillRuntimeWiringTest {
    final SqlSessionTemplate session = mock(SqlSessionTemplate.class);
    final MusicTrackMapper mapper = mock(MusicTrackMapper.class);
    final MediaDeletionAssetMapper deletion = mock(MediaDeletionAssetMapper.class);
    final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    MusicTrackRepository build() {
        when(session.getMapper(MusicTrackMapper.class)).thenReturn(mapper);
        when(session.getMapper(MediaDeletionAssetMapper.class)).thenReturn(deletion);
        return MusicCoverBackfillRuntime.buildTracks(session, assets, URI.create("https://img.example"), "blog");
    }
    @Test void assemblyAndReadsDoNotQueryDeletionColumns() {
        var tracks = build();
        verifyNoInteractions(mapper, deletion, assets);
        when(mapper.selectAdminList()).thenReturn(List.of(row(null)));
        assertEquals("track", tracks.findAdminList().get(0).track().id());
        verify(mapper).selectAdminList();
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(deletion, assets);
        verify(session).getMapper(MediaDeletionAssetMapper.class);
        verify(session).getMapper(MusicTrackMapper.class);
        verifyNoMoreInteractions(session);
    }
    @Test void metadataOutsideTransactionFailsClosed() {
        var tracks = build();
        assertEquals(MusicCatalogException.Reason.UNAVAILABLE, assertThrows(MusicCatalogException.class,
                () -> tracks.updateMetadata("track", new MusicTrackMetadata("edit", "", null, true, 0), 7, Instant.now())).reason());
        verifyNoInteractions(mapper, deletion, assets);
    }
    @Test void metadataLockFailureDoesNotFallBack() {
        var tracks = build();
        when(mapper.selectById("track")).thenReturn(row(UUID.randomUUID()));
        when(deletion.lockByIds(anySet())).thenThrow(new IllegalStateException("private SQL"));
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            var error = assertThrows(MusicCatalogException.class,
                    () -> tracks.updateMetadata("track", new MusicTrackMetadata("edit", "", null, true, 0), 7, Instant.now()));
            assertEquals(MusicCatalogException.Reason.UNAVAILABLE, error.reason());
            assertFalse(error.getMessage().contains("SQL"));
            verify(mapper, never()).updateMetadata(anyString(), any(), anyLong(), any());
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void mediaBindingOutsideTransactionFailsClosed() {
        var tracks = build();
        var error = assertThrows(MusicCatalogException.class,
                () -> tracks.bindMedia("track", MusicMediaKind.COVER, UUID.randomUUID(), 7, null, Instant.now()));
        assertEquals(MusicCatalogException.Reason.UNAVAILABLE, error.reason());
        verifyNoInteractions(mapper, deletion, assets);
    }
    @Test void mediaBindingLockFailureNeverFallsBackToBusinessWrite() {
        var tracks = build();
        var oldAudio = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var newCover = UUID.fromString("00000000-0000-0000-0000-000000000009");
        when(mapper.selectById("track")).thenReturn(row(oldAudio), row(oldAudio, newCover, 8));
        when(mapper.selectByIdForUpdate("track")).thenReturn(row(oldAudio));
        when(assets.findByLocations(eq("cloudflare_imgbed"), anySet())).thenReturn(List.of());
        when(mapper.bindCover(eq("track"), eq(newCover), eq(7L), isNull(), any(OffsetDateTime.class))).thenReturn(1);
        doThrow(new IllegalStateException("private SQL details")).when(deletion).lockByIds(anySet());
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            var error = assertThrows(MusicCatalogException.class,
                    () -> tracks.bindMedia("track", MusicMediaKind.COVER, newCover, 7, null, Instant.now()));
            assertEquals(MusicCatalogException.Reason.UNAVAILABLE, error.reason());
            assertFalse(error.getMessage().contains("SQL"));
            verify(deletion).lockByIds(argThat(ids -> ids.contains(oldAudio) && ids.contains(newCover)));
            verify(mapper, never()).selectByIdForUpdate("track");
            verify(mapper, never()).bindCover(anyString(), any(), anyLong(), any(), any());
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    static MusicTrackRecord row(UUID audio) {
        return row(audio, null, 7);
    }
    static MusicTrackRecord row(UUID audio, UUID cover, long version) {
        var time = OffsetDateTime.parse("2026-10-13T00:00:00Z");
        return new MusicTrackRecord("track", "title", "artist", null, audio, cover, null, null, true, 0, version, time, time);
    }
}
