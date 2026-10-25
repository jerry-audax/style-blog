package com.blogsystem.application.music.service;

import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.*;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;
import com.blogsystem.domain.music.gateway.SongArtworkDownloadGateway;
import com.blogsystem.domain.music.model.*;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicCoverBackfillApplicationServiceTest {
    final MusicTrackRepository tracks = mock(MusicTrackRepository.class);
    final SongArtworkDownloadGateway artwork = mock(SongArtworkDownloadGateway.class);
    final MediaUploadOperationRepository operations = mock(MediaUploadOperationRepository.class);
    final MediaUploadApplicationService uploads = mock(MediaUploadApplicationService.class);
    final UUID id = UUID.randomUUID();
    final MusicCoverBackfillApplicationService service = new MusicCoverBackfillApplicationService(tracks, artwork, operations, uploads);

    MusicTrackSnapshot row(UUID cover, String source) {
        return new MusicTrackSnapshot(new PersistedMusicTrack("song", "My title", "My artist", source,
                UUID.randomUUID(), cover, null, null, true, 0), 0, Instant.EPOCH, Instant.EPOCH);
    }

    @Test void routesDownloadedArtworkThroughTrackedCoverUploadWithoutChangingSongMetadata() throws Exception {
        when(tracks.findSnapshotById("song")).thenReturn(Optional.of(row(null, "123")));
        when(artwork.download("123")).thenReturn(Optional.of(new MusicMediaUpload(new byte[]{1,2,3}, "image/jpeg", "123.jpg", MusicMediaKind.COVER)));
        var pending = mock(MediaUploadOperation.class);
        var done = mock(MediaUploadOperation.class);
        when(uploads.create(eq(1L), any())).thenReturn(new MediaUploadApplicationService.Submission(pending, true));
        when(uploads.content(eq(1L), eq(id), any())).thenReturn(done);
        assertSame(done, service.complete(1, "song", 0L, id));
        var request = ArgumentCaptor.forClass(MediaUploadRequest.class);
        verify(uploads).create(eq(1L), request.capture());
        assertEquals(MediaUploadSlot.COVER, request.getValue().slot());
        assertEquals("song", request.getValue().targetId());
        assertEquals(0L, request.getValue().expectedVersion());
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", request.getValue().file().sourceSha256());
        var content = ArgumentCaptor.forClass(MediaUploadContent.class);
        verify(uploads).content(eq(1L), eq(id), content.capture());
        assertArrayEquals(new byte[]{1,2,3}, content.getValue().openStream().readAllBytes());
        verify(tracks, never()).updateMetadata(any(), any(), anyLong(), any());
    }

    @Test void refusesExistingCoverAndStaleVersionBeforeDownloading() {
        for (var row : new MusicTrackSnapshot[]{row(UUID.randomUUID(), "123"),
                new MusicTrackSnapshot(row(null,"123").track(), 1, Instant.EPOCH, Instant.EPOCH)}) {
            when(tracks.findSnapshotById("song")).thenReturn(Optional.of(row));
            var error = assertThrows(MusicCatalogException.class, () -> service.complete(1,"song",0L,id));
            assertEquals(MusicCatalogException.Reason.CONFLICT,error.reason());
        }
        verifyNoInteractions(artwork, uploads);
    }

    @Test void noArtworkOrSourceDoesNotCreateAnUpload() {
        when(tracks.findSnapshotById("song")).thenReturn(Optional.of(row(null, null)));
        assertThrows(MusicCatalogException.class, () -> service.complete(1,"song",0L,id));
        when(tracks.findSnapshotById("song")).thenReturn(Optional.of(row(null,"123")));
        when(artwork.download("123")).thenReturn(Optional.empty());
        assertThrows(MusicCatalogException.class, () -> service.complete(1,"song",0L,id));
        verifyNoInteractions(uploads);
    }

    @Test void replayReturnsOriginalOperationWithoutFetchingOrUploadingAgain() {
        var original = mock(MediaUploadOperation.class,RETURNS_DEEP_STUBS);
        when(original.progress().status()).thenReturn(MediaUploadStatus.UNKNOWN);
        var intent = mock(MediaUploadOperation.Intent.class);
        when(original.intent()).thenReturn(intent);
        when(intent.target()).thenReturn(new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.COVER,0L,null,null));
        when(operations.findOwned(1,id)).thenReturn(Optional.of(original));
        assertSame(original,service.complete(1,"song",0L,id));
        verifyNoInteractions(tracks,artwork,uploads);
        var error=assertThrows(MediaUploadException.class,()->service.complete(1,"other",0L,id));
        assertEquals(MediaUploadException.Reason.CONFLICT,error.reason());
    }

    @Test void missingVersionIsRejectedBeforeAnyDependencyCall() {
        assertThrows(MusicCatalogException.class,()->service.complete(1,"song",null,id));
        verifyNoInteractions(tracks,artwork,operations,uploads);
    }

    @Test void registeredButUndispatchedCoverCanResumeUsingItsOriginalFingerprint() {
        var request=new MediaUploadRequest(id,MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.COVER,0L,
                new MediaUploadFile("123.jpg","image/jpeg",3,"039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"),null);
        var original=com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy.ready(1,request,
                new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.COVER,0L,null,null),
                "cloudflare_imgbed",new com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey("blog"),UUID.randomUUID(),Instant.EPOCH);
        when(operations.findOwned(1,id)).thenReturn(Optional.of(original));
        when(tracks.findSnapshotById("song")).thenReturn(Optional.of(row(null,"123")));
        when(artwork.download("123")).thenReturn(Optional.of(new MusicMediaUpload(new byte[]{1,2,3},"image/jpeg","123.jpg",MusicMediaKind.COVER)));
        when(uploads.create(eq(1L),any())).thenReturn(new MediaUploadApplicationService.Submission(original,false));
        var done=mock(MediaUploadOperation.class);when(uploads.content(eq(1L),eq(id),any())).thenReturn(done);
        assertSame(done,service.complete(1,"song",0L,id));
    }

    @Test void unresolvedManualCoverBlocksANewIdEvenAfterSongMetadataWasEdited() {
        var original=mock(MediaUploadOperation.class);
        when(operations.findUnresolvedMusicCover(1,"song")).thenReturn(Optional.of(original));
        assertSame(original,service.complete(1,"song",9L,id));
        verifyNoInteractions(tracks,artwork,uploads);
    }
}
