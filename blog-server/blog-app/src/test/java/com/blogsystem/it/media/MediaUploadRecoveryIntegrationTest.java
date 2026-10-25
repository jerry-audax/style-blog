package com.blogsystem.it.media;

import com.blogsystem.app.it.application.asset.upload.service.FakeMediaUploadStorageRepository;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCandidate;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadProbe;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStorageException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;
import com.blogsystem.domain.music.model.MusicTrackMetadata;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaUploadRecoveryIntegrationTest {
    @Test void lateInitReceiptCannotCommitAfterTakeover() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()) {
            var service=f.service();var request=MediaUploadWorkflowFixture.request(16000001,MediaUploadSlot.AUDIO,7);service.create(1,request);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
            f.storage.before=name->{if(name.equals("init")){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}};
            try{
                var sending=pool.submit(()->service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(16000001)));assertTrue(entered.await(5,TimeUnit.SECONDS));
                var held=service.get(1,request.operationId());expire(f,held);var recovered=service.recover(1,request.operationId(),held.version());assertEquals(MediaUploadStatus.UNKNOWN,recovered.progress().status());
                release.countDown();var error=assertThrows(ExecutionException.class,()->sending.get(10,TimeUnit.SECONDS));assertInstanceOf(MediaUploadException.class,error.getCause());
                var current=service.get(1,request.operationId());assertNull(current.progress().providerUploadId());assertEquals(0,current.progress().confirmedChunks());assertEquals(List.of("init","probe"),f.storage.calls);
            }finally{release.countDown();pool.shutdownNow();}
        }
    }
    @Test void initChunkMergeUnknownNeverResends() throws Exception {
        for(String stage:List.of("init","chunk:0","merge"))try(var f=new MediaUploadWorkflowFixture()){
            var service=f.service();var request=MediaUploadWorkflowFixture.request(16000001,MediaUploadSlot.AUDIO,7);service.create(1,request);
            f.storage.before=name->{if(name.equals(stage))throw new MediaUploadStorageException(MediaUploadStorageException.Outcome.UNKNOWN,MediaUploadErrorCode.PROVIDER_UNKNOWN);};
            var result=service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(16000001));assertEquals(MediaUploadStatus.UNKNOWN,result.progress().status());
            List<String> original=List.copyOf(f.storage.calls);service.get(1,request.operationId());service.history(1,0,20);assertEquals(original,f.storage.calls);
            var recovered=service.recover(1,request.operationId(),result.version());assertEquals(MediaUploadStatus.UNKNOWN,recovered.progress().status());assertEquals(original.size()+1,f.storage.calls.size());assertEquals("probe",f.storage.calls.get(f.storage.calls.size()-1));
        }
    }
    @Test void uploadedRecoveryUsesNoProvider() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var ops=spy(f.operations);var failing=new AtomicBoolean(true);
            doAnswer(a->{if(failing.get())throw new IllegalStateException("Before durable success");return a.callRealMethod();}).when(ops).complete(any(),any());
            var service=f.service(ops,f.assets,f.ports,f.tx);var request=MediaUploadWorkflowFixture.request(1,MediaUploadSlot.AUDIO,7);service.create(1,request);
            assertThrows(MediaUploadException.class,()->service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(1)));var saved=service.get(1,request.operationId());assertEquals(MediaUploadStatus.UPLOADED,saved.progress().status());
            failing.set(false);expire(f,saved);var result=service.recover(1,request.operationId(),saved.version());assertEquals(MediaUploadStatus.SUCCEEDED,result.progress().status());assertEquals(2,result.progress().finalizeAttempts());assertEquals(List.of("direct"),f.storage.calls);
        }
    }
    @Test void reliableCandidateFinalizesButAmbiguityNeverAdopts() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var service=f.service();var request=MediaUploadWorkflowFixture.request(1,MediaUploadSlot.AUDIO,7);var created=service.create(1,request).operation();
            f.storage.before=name->{if(name.equals("direct"))throw new MediaUploadStorageException(MediaUploadStorageException.Outcome.UNKNOWN,MediaUploadErrorCode.PROVIDER_UNKNOWN);};
            var current=service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(1));var candidate=new MediaUploadCandidate(FakeMediaUploadStorageRepository.receipt(created.intent()).objectKey(),MediaKind.AUDIO,1L,"telegram","configured");
            for(var probe:List.of(new MediaUploadProbe(false,List.of(candidate)),new MediaUploadProbe(true,List.of(candidate,new MediaUploadCandidate(new MediaObjectKey(created.intent().operationFolder().value()+"/second"),MediaKind.AUDIO,1L,"telegram","configured"))))){f.storage.probeResult=probe;current=service.recover(1,request.operationId(),current.version());assertEquals(MediaUploadStatus.UNKNOWN,current.progress().status());assertTrue(f.assets.findById(created.intent().candidateAssetId()).isEmpty());}
            f.storage.probeResult=new MediaUploadProbe(true,List.of(candidate));current=service.recover(1,request.operationId(),current.version());assertEquals(MediaUploadStatus.SUCCEEDED,current.progress().status());assertEquals(List.of("direct","probe","probe","probe"),f.storage.calls);
        }
    }
    @Test void conflictKeepsOldBindingsAndAllowsOneExplicitChild() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var service=f.service();var request=MediaUploadWorkflowFixture.request(1,MediaUploadSlot.AUDIO,7);var original=service.create(1,request).operation();
            f.tx.execute(s->f.music.updateMetadata(request.targetId(),new MusicTrackMetadata("changed","",null,true,0),7,java.time.Instant.now()));
            var conflict=service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(1));assertEquals(MediaUploadStatus.CONFLICT,conflict.progress().status());assertTrue(f.assets.findById(conflict.intent().candidateAssetId()).isEmpty());assertEquals(UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO),f.music.findSnapshotById(request.targetId()).orElseThrow().track().audioAssetId());
            UUID childId=UUID.randomUUID();var child=service.bind(1,request.operationId(),childId,8,conflict.version()).operation();assertEquals(MediaUploadStatus.SUCCEEDED,child.progress().status());assertEquals(childId,service.get(1,request.operationId()).outcome().resolvedByOperationId());assertEquals(original.intent(),service.get(1,request.operationId()).intent());
            assertFalse(service.bind(1,request.operationId(),childId,8,conflict.version()).created());
            assertThrows(MediaUploadException.class,()->service.bind(1,request.operationId(),UUID.randomUUID(),9,service.get(1,request.operationId()).version()));assertEquals(List.of("direct"),f.storage.calls);
        }
    }
    @Test void twoSessionsHaveOnlyOneSuccessfulChild() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var service=f.service();var request=MediaUploadWorkflowFixture.request(1,MediaUploadSlot.AUDIO,7);service.create(1,request);f.tx.execute(s->f.music.updateMetadata(request.targetId(),new MusicTrackMetadata("changed","",null,true,0),7,java.time.Instant.now()));var source=service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(1));
            List<UUID> children=new ArrayList<>();for(int n=0;n<2;n++){UUID id=UUID.randomUUID();var target=f.ports.get(0).prepareTarget(new MediaUploadRequest(id,MediaUploadTargetType.MUSIC_TRACK,request.targetId(),MediaUploadSlot.AUDIO,8L,request.file(),null),source.intent().candidateAssetId());var child=MediaUploadPolicy.binding(1,source,id,target,source.version(),java.time.Instant.now());f.tx.execute(s->f.operations.insert(child));children.add(id);}
            var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var ready=new CountDownLatch(2);
            try{List<Future<MediaUploadOperation>> results=new ArrayList<>();for(UUID id:children)results.add(pool.submit(()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return service.recover(1,id,0);}));assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();int successes=0;for(var result:results)if(result.get(15,TimeUnit.SECONDS).progress().status()==MediaUploadStatus.SUCCEEDED)successes++;assertEquals(1,successes);assertEquals(9,f.music.findSnapshotById(request.targetId()).orElseThrow().version());assertEquals(List.of("direct"),f.storage.calls);}finally{start.countDown();pool.shutdownNow();}
        }
    }
    @Test void stableVideoIdSurvivesRecovery() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()){
            var ops=spy(f.operations);var failed=new AtomicBoolean(true);doAnswer(a->{if(failed.get())throw new IllegalStateException("Test commit interruption");return a.callRealMethod();}).when(ops).complete(any(),any());
            var service=f.service(ops,f.assets,f.ports,f.tx);var request=MediaUploadWorkflowFixture.request(209715200,MediaUploadSlot.VIDEO,0);var original=service.create(1,request).operation();
            assertThrows(MediaUploadException.class,()->service.content(1,request.operationId(),MediaUploadWorkflowFixture.zeros(209715200)));var saved=service.get(1,request.operationId());assertTrue(f.videos.findSnapshotById(original.intent().target().id()).isEmpty());
            int calls=f.storage.calls.size();failed.set(false);expire(f,saved);var done=service.recover(1,request.operationId(),saved.version());assertEquals(original.intent().target().id(),done.outcome().result().targetId());assertEquals(0,f.videos.findSnapshotById(done.intent().target().id()).orElseThrow().version());assertEquals(calls,f.storage.calls.size());
        }
    }
    static void expire(MediaUploadWorkflowFixture f,MediaUploadOperation op)throws Exception{f.execute("UPDATE media_upload_operation SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+op.intent().id()+"'");}
}
