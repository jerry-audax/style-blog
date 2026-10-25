package com.blogsystem.it.media;

import com.blogsystem.application.asset.upload.port.UploadBindingApplicationPort;
import com.blogsystem.application.asset.upload.port.UploadBindingPreparation;

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;

import org.springframework.transaction.support.TransactionCallback;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.ChunkConfirmed;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException.Reason;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Intent;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionOperations;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaUploadWorkflowIntegrationTest {
    @Test void checkpointFailureStopsNextDispatch() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var ops=spy(f.operations);
            doAnswer(a->{if(a.getArgument(1) instanceof MediaUploadCheckpoint.ChunkConfirmed)throw new MediaUploadException(MediaUploadException.Reason.UNAVAILABLE);return a.callRealMethod();}).when(ops).checkpoint(any(),any());
            var service=f.service(ops,f.assets,f.ports,f.tx);var request=MediaUploadWorkflowFixture.request(16000001,MediaUploadSlot.AUDIO,7);service.create(1,request);
            assertThrows(MediaUploadException.class,()->service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(16000001)));
            assertEquals(List.of("init","chunk:0"),f.storage.calls);var saved=service.get(1,request.operationId());assertEquals(0,saved.progress().confirmedChunks());assertEquals(0,saved.progress().inFlightChunk());assertNull(saved.outcome().result());
            f.execute("UPDATE media_upload_operation SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+request.operationId()+"'");
            var unknown=service.recover(1,request.operationId(),saved.version());assertEquals(MediaUploadStatus.UNKNOWN,unknown.progress().status());assertEquals(List.of("init","chunk:0","probe"),f.storage.calls);
        }
    }
    @Test void rollbackAfterAssetOrBusinessWriteLeavesNoPartialBinding() throws Exception {
        for(int stage=0;stage<3;stage++)try(var f=new MediaUploadWorkflowFixture()){
            final int failAt=stage;var original=f.ports.get(0);
            UploadBindingApplicationPort failing=new UploadBindingApplicationPort(){
                public MediaUploadTargetType targetType(){return original.targetType();}
                public MediaUploadTarget prepareTarget(MediaUploadRequest request,java.util.UUID candidate){return original.prepareTarget(request,candidate);}
                public UploadBindingPreparation prepareCommit(MediaUploadOperation.Intent intent){var prepared=original.prepareCommit(intent);return (resource,now)->{
                    if(failAt==0)throw new IllegalStateException("test fail after asset");var result=(MediaUploadResultSnapshot)prepared.commit(resource,now);
                    if(failAt==1)throw new IllegalStateException("test fail after business");
                    return new MediaUploadResultSnapshot(result.targetType(),"different-target",result.targetVersion(),result.title(),result.artist(),result.sourceId(),result.enabled(),result.sortOrder(),result.externalCoverUrl(),result.audio(),result.cover(),result.lyrics(),result.video(),result.createdAt(),result.updatedAt());
                };}
            };
            var service=f.service(f.operations,f.assets,List.of(failing,f.ports.get(1)),f.tx);var request=MediaUploadWorkflowFixture.request(1,MediaUploadSlot.AUDIO,7);service.create(1,request);
            assertEquals(MediaUploadException.Reason.UNAVAILABLE,assertThrows(MediaUploadException.class,()->service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(1))).reason());
            var saved=service.get(1,request.operationId());assertEquals(MediaUploadStatus.UPLOADED,saved.progress().status());assertEquals(1,saved.progress().finalizeAttempts());assertNull(saved.outcome().result());
            assertTrue(f.assets.findById(saved.intent().candidateAssetId()).isEmpty());assertEquals(7,f.music.findSnapshotById(request.targetId()).orElseThrow().version());assertEquals(List.of("direct"),f.storage.calls);
        }
    }
    @Test void lostCommitResponseReadsSucceededSnapshot() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var fail=new AtomicBoolean(true);
            TransactionOperations transaction=new TransactionOperations(){public <T>T execute(TransactionCallback<T> callback){T result=f.tx.execute(callback);if(result instanceof MediaUploadOperation op&&op.progress().status()==MediaUploadStatus.SUCCEEDED&&fail.getAndSet(false))throw new IllegalStateException("Commit response lost after real commit");return result;}};
            var service=f.service(f.operations,f.assets,f.ports,transaction);var request=MediaUploadWorkflowFixture.request(1,MediaUploadSlot.AUDIO,7);service.create(1,request);
            var result=service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(1));assertEquals(MediaUploadStatus.SUCCEEDED,result.progress().status());assertTrue(f.assets.findById(result.intent().candidateAssetId()).isPresent());assertEquals(8,f.music.findSnapshotById(request.targetId()).orElseThrow().version());
            var snapshot=result.outcome().result();f.tx.execute(s->f.music.updateMetadata(request.targetId(),new com.blogsystem.domain.music.model.MusicTrackMetadata("later","",null,true,0),8,java.time.Instant.now()));
            assertEquals(snapshot,service.recover(1,request.operationId(),0).outcome().result());assertEquals(List.of("direct"),f.storage.calls);
        }
    }
}
