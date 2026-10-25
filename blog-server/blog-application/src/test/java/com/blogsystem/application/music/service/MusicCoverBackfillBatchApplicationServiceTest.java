package com.blogsystem.application.music.service;
import com.blogsystem.domain.music.model.*;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicCoverBackfillBatchApplicationServiceTest {
    MusicTrackSnapshot row(String id,String source,UUID cover) {
        return new MusicTrackSnapshot(new PersistedMusicTrack(id,id,"Artist",source,null,cover,null,null,true,0),0,Instant.EPOCH,Instant.EPOCH);
    }
    @Test void batchSkipsCustomCoversAndUnknownSourcesAndUsesTheSameOperationOnRerun() {
        var tracks=mock(MusicTrackRepository.class);
        var covers=mock(MusicCoverBackfillApplicationService.class);
        when(tracks.findAdminList()).thenReturn(List.of(row("known","123",null),row("custom","234",UUID.randomUUID()),row("missing",null,null)));
        var operation=mock(MediaUploadOperation.class,RETURNS_DEEP_STUBS);
        when(operation.progress().status()).thenReturn(MediaUploadStatus.SUCCEEDED);
        List<UUID> ids=new ArrayList<>();
        when(covers.complete(eq(1L),eq("known"),eq(0L),any())).thenAnswer(call->{ids.add(call.getArgument(3));return operation;});
        var batch=new MusicCoverBackfillBatchApplicationService(tracks,covers);
        assertEquals(List.of("SUCCEEDED","SKIPPED_EXISTING","MISSING_SOURCE"),batch.run(1,100).stream().map(MusicCoverBackfillBatchApplicationService.Item::status).toList());
        batch.run(1,100);
        assertEquals(ids.get(0),ids.get(1));
        verify(covers,never()).complete(eq(1L),eq("custom"),any(),any());
        verify(covers,never()).complete(eq(1L),eq("missing"),any(),any());
    }
}
