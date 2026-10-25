package com.blogsystem.app.it.application.asset.upload.service;

import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException.Reason;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import com.blogsystem.it.media.MediaUploadWorkflowFixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaUploadApplicationServiceTest {
    @Test void movingUseCaseDoesNotRenameItsContentContract() throws Exception {
        assertEquals(com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.class,
                MediaUploadApplicationService.class
                        .getMethod("content", long.class, UUID.class, MediaUploadContent.class)
                        .getReturnType());
    }
    @Test void wrongResumeSourceRetainsSessionAndConfirmedBlocksForCorrectFile() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()) {
            var service=f.service();var request=MediaUploadWorkflowFixture.request(16000001,MediaUploadSlot.AUDIO,7);service.create(1,request);
            f.storage.before=name->{if(name.equals("chunk:0")){var op=service.get(1,request.operationId());service.pause(1,request.operationId(),op.version());}};
            var paused=service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(16000001));String session=paused.progress().providerUploadId();
            assertEquals(MediaUploadStatus.PAUSED,paused.progress().status());assertEquals(1,paused.progress().confirmedChunks());f.storage.before=ignored->{};
            assertThrows(MediaUploadException.class,()->service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(2)));
            var retained=service.get(1,request.operationId());assertEquals(MediaUploadStatus.PAUSED,retained.progress().status());assertEquals(MediaUploadStage.CHUNK,retained.progress().stage());assertEquals(session,retained.progress().providerUploadId());assertEquals(1,retained.progress().confirmedChunks());assertNull(retained.lease().owner());assertEquals(List.of("init","chunk:0"),f.storage.calls);
            var wrongHash=new MediaUploadContent(){
                public long size(){return 16000001;}public String fileName(){return "wrong.mp3";}public String mediaType(){return "audio/mpeg";}
                public java.io.InputStream openStream() throws java.io.IOException {return new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(new byte[]{1}),MediaUploadWorkflowFixture.zeros(16000000).openStream());}
            };
            assertEquals(MediaUploadErrorCode.HASH_MISMATCH,assertThrows(MediaUploadException.class,()->service.content(1,request.operationId(),wrongHash)).code());
            retained=service.get(1,request.operationId());assertEquals(MediaUploadStatus.PAUSED,retained.progress().status());assertEquals(session,retained.progress().providerUploadId());assertEquals(1,retained.progress().confirmedChunks());assertNull(retained.lease().owner());assertEquals(List.of("init","chunk:0"),f.storage.calls);
            assertEquals(MediaUploadStatus.SUCCEEDED,service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(16000001)).progress().status());assertEquals(List.of("init","chunk:0","chunk:1","merge"),f.storage.calls);
        }
    }
    @Test void pauseAfterLastShortChunkResumesOnlyMerge() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()) {
            var service=f.service();var request=MediaUploadWorkflowFixture.request(16000001,MediaUploadSlot.AUDIO,7);service.create(1,request);
            f.storage.before=name->{if(name.equals("chunk:1")){var op=service.get(1,request.operationId());service.pause(1,request.operationId(),op.version());}};
            var paused=service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(16000001));assertEquals(MediaUploadStatus.PAUSED,paused.progress().status());assertEquals(2,paused.progress().confirmedChunks());
            f.storage.before=ignored->{};
            assertEquals(MediaUploadStatus.SUCCEEDED,service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(16000001)).progress().status());
            assertEquals(List.of("init","chunk:0","chunk:1","merge"),f.storage.calls);
        }
    }
    @Test void sameIntentDoesNotDispatchTwice() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var service=f.service();var request=MediaUploadWorkflowFixture.request(1,MediaUploadSlot.AUDIO,7);
            assertTrue(service.create(1,request).created());assertFalse(service.create(1,request).created());
            assertEquals(MediaUploadStatus.SUCCEEDED,service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(1)).progress().status());
            assertFalse(service.create(1,request).created());service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(1));
            assertEquals(List.of("direct"),f.storage.calls);
            assertEquals(8,f.music.findSnapshotById(request.targetId()).orElseThrow().version());
        }
    }
    @Test void verifyFailsBeforeAnyExternalCall() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var request=MediaUploadWorkflowFixture.request(1,MediaUploadSlot.AUDIO,7);var service=f.service();service.create(1,request);
            assertEquals(MediaUploadException.Reason.INVALID_REQUEST,assertThrows(MediaUploadException.class,()->service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(2))).reason());
            assertTrue(f.storage.calls.isEmpty());assertEquals(MediaUploadStatus.FAILED,service.get(1,request.operationId()).progress().status());
            assertEquals(7,f.music.findSnapshotById(request.targetId()).orElseThrow().version());
        }
    }
    @Test void directOrSequentialChunksFinalizeOnce() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var service=f.service();var direct=MediaUploadWorkflowFixture.request(16000000,MediaUploadSlot.AUDIO,7);service.create(1,direct);
            assertEquals(MediaUploadStatus.SUCCEEDED,service.content(1,direct.operationId(),MediaUploadWorkflowFixture.zeros(16000000)).progress().status());
            assertEquals(List.of("direct"),f.storage.calls);f.storage.calls.clear();
            var video=MediaUploadWorkflowFixture.request(209715200,MediaUploadSlot.VIDEO,0);service.create(1,video);
            var result=service.content(1,video.operationId(),MediaUploadWorkflowFixture.zeros(209715200));
            assertEquals(MediaUploadStatus.SUCCEEDED,result.progress().status());assertEquals(1,result.progress().finalizeAttempts());assertEquals(14,result.progress().confirmedChunks());
            assertEquals("init",f.storage.calls.get(0));for(int i=0;i<14;i++)assertEquals("chunk:"+i,f.storage.calls.get(i+1));assertEquals("merge",f.storage.calls.get(15));assertEquals(16,f.storage.calls.size());
            assertEquals(1715200,f.storage.chunkLengths.get(13));for(int i=0;i<13;i++)assertEquals(16000000,f.storage.chunkLengths.get(i));
            assertEquals(result.outcome().result().targetId(),result.intent().target().id());
        }
    }
    @Test void pauseWaitsForCheckpointAndResumeSkipsConfirmedChunks() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var service=f.service();var request=MediaUploadWorkflowFixture.request(16000001,MediaUploadSlot.AUDIO,7);service.create(1,request);
            f.storage.before=name->{if(name.equals("chunk:0")){var op=service.get(1,request.operationId());service.pause(1,request.operationId(),op.version());}};
            var paused=service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(16000001));
            assertEquals(MediaUploadStatus.PAUSED,paused.progress().status());assertEquals(1,paused.progress().confirmedChunks());assertNull(paused.lease().owner());assertEquals(List.of("init","chunk:0"),f.storage.calls);
            f.storage.before=ignored->{};
            var done=service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(16000001));assertEquals(MediaUploadStatus.SUCCEEDED,done.progress().status());assertEquals(List.of("init","chunk:0","chunk:1","merge"),f.storage.calls);
        }
    }
    @Test void activeLeaseReturnsCurrentStatus() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var service=f.service();var request=MediaUploadWorkflowFixture.request(1,MediaUploadSlot.AUDIO,7);service.create(1,request);
            f.tx.execute(s->f.operations.claimContent(1,request.operationId(),UUID.randomUUID()).orElseThrow());
            assertEquals(MediaUploadStatus.UPLOADING,service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(1)).progress().status());assertTrue(f.storage.calls.isEmpty());
        }
    }
}
