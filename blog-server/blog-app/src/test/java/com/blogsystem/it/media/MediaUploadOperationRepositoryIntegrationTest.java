package com.blogsystem.it.media;

import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaUploadOperationRepository;
import com.blogsystem.infrastructure.mybatis.codec.asset.MediaUploadSnapshotCodec;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaUploadOperationConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaUploadOperationMapper;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadExecutionFence;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.BeginRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.ChunkConfirmed;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.Initialized;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.Pause;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.Receipt;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException.Reason;
import com.blogsystem.it.media.MediaPostgresTestSupport.ScopedSchema;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Assertions;

import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class MediaUploadOperationRepositoryIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-14T00:00:00.123456789Z");

    @Test void unresolvedCoverLookupIsOwnerAndTargetScoped() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var repository=repo(session);
            var request=new MediaUploadRequest(UUID.randomUUID(),MediaUploadTargetType.MUSIC_TRACK,"cover-song",MediaUploadSlot.COVER,0L,new MediaUploadFile("123.jpg","image/jpeg",3,"a".repeat(64)),null);
            var operation=MediaUploadPolicy.ready(1,request,new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"cover-song",MediaUploadSlot.COVER,0L,null,null),"cloudflare_imgbed",new MediaObjectKey("blog"),UUID.randomUUID(),NOW);
            repository.insert(operation);session.commit();
            assertEquals(operation.intent().id(),repository.findUnresolvedMusicCover(1,"cover-song").orElseThrow().intent().id());
            assertTrue(repository.findUnresolvedMusicCover(2,"cover-song").isEmpty());
            assertTrue(repository.findUnresolvedMusicCover(1,"other-song").isEmpty());
        }
    }

    @Test void duplicateIdNeverOverwritesIntent() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var repository=repo(session);var original=ready(UUID.randomUUID(),1,1,NOW);
            repository.insert(original);session.commit();assertEquals(original,repository.findOwned(1,original.intent().id()).orElseThrow());
            assertEquals(MediaUploadException.Reason.CONFLICT,assertThrows(MediaUploadException.class,()->repository.insert(ready(original.intent().id(),1,2,NOW))).reason());
            session.rollback();assertEquals(original,repository.findOwned(1,original.intent().id()).orElseThrow());
        }
    }

    @Test void actorCannotReadOrMutateAnotherOperation() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var repository=repo(session);var original=ready(UUID.randomUUID(),1,1,NOW);repository.insert(original);session.commit();
            assertTrue(repository.findOwned(2,original.intent().id()).isEmpty());
            assertTrue(repository.findOwnedForUpdate(2,original.intent().id()).isEmpty());
            assertTrue(repository.claimContent(2,original.intent().id(),UUID.randomUUID()).isEmpty());
            assertEquals(0,repository.findHistory(2,0,20).total());
            assertEquals(MediaUploadException.Reason.NOT_FOUND,assertThrows(MediaUploadException.class,()->repository.requestPause(2,original.intent().id(),0)).reason());
            session.rollback();assertEquals(original,repository.findOwned(1,original.intent().id()).orElseThrow());
        }
    }

    @Test void pgClockClaimHasOneWinner() throws Exception {
        try(var schema=fixture()) {
            var factory=factory(schema.name());var original=ready(UUID.randomUUID(),1,1,Instant.EPOCH);
            try(var session=factory.openSession(false)){repo(session).insert(original);session.commit();}
            var pool=Executors.newFixedThreadPool(2);var waiting=new CountDownLatch(2);var start=new CountDownLatch(1);
            try {
                var left=pool.submit(()->claim(factory,original.intent().id(),waiting,start));
                var right=pool.submit(()->claim(factory,original.intent().id(),waiting,start));
                assertTrue(waiting.await(5,TimeUnit.SECONDS));start.countDown();
                assertTrue(left.get(10,TimeUnit.SECONDS)^right.get(10,TimeUnit.SECONDS));
                try(var session=factory.openSession(false)) {
                    var loaded=repo(session).findOwned(1,original.intent().id()).orElseThrow();
                    assertEquals(1,loaded.lease().fencingToken());assertEquals(1,loaded.progress().executionAttempts());
                    assertEquals(MediaUploadStage.VERIFY,loaded.progress().stage());
                    assertTrue(loaded.lease().until().isAfter(Instant.now().plusSeconds(120)));
                    assertTrue(loaded.lease().until().isBefore(Instant.now().plusSeconds(185)));
                }
            } finally {start.countDown();pool.shutdownNow();}
        }
    }

    @Test void heartbeatAndPauseSurviveChunkCheckpoint() throws Exception {
        try(var schema=fixture()) {
            var factory=factory(schema.name());UUID id=UUID.randomUUID();MediaUploadExecutionFence fence;
            try(var session=factory.openSession(false)) {
                var r=repo(session);r.insert(ready(id,1,16000001,NOW));session.commit();
                var claimed=r.claimContent(1,id,UUID.randomUUID()).orElseThrow();fence=fence(claimed);session.commit();
                r.checkpoint(fence,new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.INIT,null));session.commit();
                r.checkpoint(fence,new MediaUploadCheckpoint.Initialized("session-1"));session.commit();
                r.checkpoint(fence,new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.CHUNK,0));session.commit();
                assertFalse(r.findOwned(1,id).orElseThrow().progress().pauseRequested());
                try(var other=factory.openSession(false)) {
                    var observer=repo(other);var version=observer.findOwned(1,id).orElseThrow().version();
                    observer.requestPause(1,id,version);other.commit();
                    assertTrue(observer.renewLease(fence));other.commit();
                }
                var saved=r.checkpoint(fence,new MediaUploadCheckpoint.ChunkConfirmed(0));session.commit();
                assertTrue(saved.progress().pauseRequested());assertEquals(1,saved.progress().confirmedChunks());
                assertEquals(1,saved.lease().fencingToken());assertNull(saved.progress().inFlightChunk());
                assertThrows(MediaUploadException.class,()->r.checkpoint(fence,new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.CHUNK,1)));
                session.rollback();var paused=r.checkpoint(fence,new MediaUploadCheckpoint.Pause());session.commit();
                assertEquals(MediaUploadStatus.PAUSED,paused.progress().status());assertNull(paused.lease().owner());
                var resumed=r.claimContent(1,id,UUID.randomUUID()).orElseThrow();session.commit();
                assertEquals(2,resumed.lease().fencingToken());assertEquals("session-1",resumed.progress().providerUploadId());
                assertEquals(1,resumed.progress().confirmedChunks());assertFalse(resumed.progress().pauseRequested());
            }
        }
    }

    @Test void expiredFenceCannotSaveOrRenew() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var r=repo(session);UUID id=UUID.randomUUID();r.insert(ready(id,1,1,NOW));session.commit();
            var claimed=r.claimContent(1,id,UUID.randomUUID()).orElseThrow();var old=fence(claimed);session.commit();
            r.checkpoint(old,new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.DIRECT,null));session.commit();
            schema.execute("UPDATE media_upload_operation SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+id+"'");
            assertFalse(r.renewLease(old));session.commit();
            var before=r.findOwned(1,id).orElseThrow();var recovered=r.claimRecovery(1,id,before.version(),UUID.randomUUID()).orElseThrow();session.commit();
            assertEquals(MediaUploadStatus.UNKNOWN,recovered.progress().status());assertEquals(2,recovered.lease().fencingToken());
            assertThrows(MediaUploadException.class,()->r.checkpoint(old,new MediaUploadCheckpoint.Receipt(key(recovered))));session.rollback();
            assertFalse(r.renewLease(old));session.rollback();
            assertEquals(recovered,r.findOwned(1,id).orElseThrow());
        }
    }

    @Test void largeVersionRoundTripsWithoutOverflow() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var r=repo(session);UUID id=UUID.randomUUID();r.insert(ready(id,1,1,NOW));session.commit();
            schema.execute("UPDATE media_upload_operation SET version=9007199254740993 WHERE id='"+id+"'");
            assertEquals(9007199254740993L,r.findOwned(1,id).orElseThrow().version());
            var claim=r.claimContent(1,id,UUID.randomUUID()).orElseThrow();session.commit();assertEquals(9007199254740994L,claim.version());
            schema.execute("UPDATE media_upload_operation SET version=9223372036854775807 WHERE id='"+id+"'");
            assertFalse(r.renewLease(fence(claim)));session.commit();
            assertThrows(MediaUploadException.class,()->r.checkpoint(fence(claim),new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.DIRECT,null)));session.rollback();
            assertEquals(Long.MAX_VALUE,r.findOwned(1,id).orElseThrow().version());
        }
    }

    @Test void historyIsReadOnlyAndStable() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var r=repo(session);var first=UUID.fromString("00000000-0000-0000-0000-000000000401");
            var second=UUID.fromString("00000000-0000-0000-0000-000000000402");var older=UUID.fromString("00000000-0000-0000-0000-000000000403");
            r.insert(ready(second,1,1,NOW));r.insert(ready(first,1,1,NOW));r.insert(ready(older,1,1,NOW.minusSeconds(1)));session.commit();
            String before=fingerprint(schema);var page=r.findHistory(1,1,2);
            assertEquals(3,page.total());assertEquals(List.of(second,older),page.records().stream().map(o->o.intent().id()).toList());
            assertEquals(before,fingerprint(schema));
        }
    }

    @Test void completionFreezesResultAndRollbackKeepsReceipt() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var r=repo(session);UUID id=UUID.randomUUID();r.insert(ready(id,1,1,NOW));session.commit();
            var claimed=r.claimContent(1,id,UUID.randomUUID()).orElseThrow();var fence=fence(claimed);session.commit();
            r.checkpoint(fence,new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.DIRECT,null));session.commit();
            var receipt=r.checkpoint(fence,new MediaUploadCheckpoint.Receipt(key(claimed)));session.commit();
            var value=result(receipt,1);r.complete(fence,value);session.rollback();
            assertEquals(MediaUploadStatus.UPLOADED,r.findOwned(1,id).orElseThrow().progress().status());
            var completed=r.complete(fence,value);session.commit();
            assertEquals(MediaUploadStatus.SUCCEEDED,completed.progress().status());assertNull(completed.lease().owner());
            assertEquals(value,r.findOwned(1,id).orElseThrow().outcome().result());
            assertEquals("  Existing-视频-ID  ",completed.outcome().result().targetId());
        }
    }

    @Test void conflictReceiptAllowsOnlyOneResolvedChildAndNeverChangesOriginalIntent() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var r=repo(session);UUID id=UUID.randomUUID();var original=ready(id,1,1,NOW);r.insert(original);session.commit();
            schema.execute("UPDATE media_upload_operation SET status='CONFLICT',stage='PERSIST',confirmed_chunks=1,confirmed_object_key=operation_folder||'/a.mp3',version=7 WHERE id='"+id+"'");
            var source=r.findOwned(1,id).orElseThrow();var target=new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,source.intent().target().id(),MediaUploadSlot.AUDIO,8L,null,null);
            var left=MediaUploadPolicy.binding(1,source,UUID.randomUUID(),target,7,NOW);var right=MediaUploadPolicy.binding(1,source,UUID.randomUUID(),target,7,NOW);
            r.insert(left);r.insert(right);session.commit();
            left=r.claimRecovery(1,left.intent().id(),0,UUID.randomUUID()).orElseThrow();
            right=r.claimRecovery(1,right.intent().id(),0,UUID.randomUUID()).orElseThrow();session.commit();
            r.resolveSource(fence(left),id,left.intent().id());r.complete(fence(left),result(left,9));session.commit();
            var loser=right;assertThrows(MediaUploadException.class,()->r.resolveSource(fence(loser),id,loser.intent().id()));session.rollback();
            var resolved=r.findOwned(1,id).orElseThrow();assertEquals(original.intent(),resolved.intent());
            assertEquals(left.intent().id(),resolved.outcome().resolvedByOperationId());assertEquals(MediaUploadStatus.CONFLICT,resolved.progress().status());
            assertEquals(MediaUploadStatus.UPLOADED,r.findOwned(1,right.intent().id()).orElseThrow().progress().status());
        }
    }

    @Test void unavailableDatabaseHasSafeErrorWithoutSql() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var r=repo(session);schema.execute("ALTER TABLE media_upload_operation RENAME TO unavailable_uploads");
            var failure=assertThrows(MediaUploadException.class,()->r.findOwned(1,UUID.randomUUID()));
            assertEquals(MediaUploadException.Reason.UNAVAILABLE,failure.reason());assertFalse(failure.getMessage().contains("SELECT"));
        }
    }

    static MediaPostgresTestSupport.ScopedSchema fixture() throws Exception {
        var schema=MediaPostgresTestSupport.createSchema();
        try {schema.executeDdl();schema.executeDdl(MediaUploadOperationDdlIntegrationTest.DDL);return schema;}
        catch(Exception failure){schema.close();throw failure;}
    }
    static SqlSessionFactory factory(String schema) throws Exception {
        var factory=MediaRuntimeRepositoryIntegrationTest.factory(schema);factory.getConfiguration().addMapper(MediaUploadOperationMapper.class);return factory;
    }
    static MediaUploadOperationRepository repo(SqlSession session) {
        return new MybatisMediaUploadOperationRepository(session.getMapper(MediaUploadOperationMapper.class),new MediaUploadOperationConverter(new MediaUploadSnapshotCodec(new ObjectMapper())));
    }
    static MediaUploadOperation ready(UUID id,long actor,long size,Instant now) {
        var target=new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"  Existing-视频-ID  ",MediaUploadSlot.AUDIO,0L,null,null);
        var request=new MediaUploadRequest(id,MediaUploadTargetType.MUSIC_TRACK,target.id(),MediaUploadSlot.AUDIO,0L,new MediaUploadFile("歌曲.mp3","audio/mpeg",size,"a".repeat(64)),null);
        return MediaUploadPolicy.ready(actor,request,target,"cloudflare_imgbed",new MediaObjectKey("blog"),UUID.randomUUID(),now);
    }
    static MediaUploadExecutionFence fence(MediaUploadOperation op) {return new MediaUploadExecutionFence(op.intent().id(),op.intent().actorId(),op.lease().owner(),op.lease().fencingToken());}
    static MediaObjectKey key(MediaUploadOperation op) {return new MediaObjectKey(op.intent().operationFolder().value()+"/a.mp3");}
    static MediaUploadResultSnapshot result(MediaUploadOperation op,long version) {
        var f=op.intent().file();var resource=new MediaUploadResourceSnapshot(op.intent().candidateAssetId(),op.intent().provider(),op.outcome().confirmedObjectKey(),MediaKind.AUDIO,MediaAssetStatus.ACTIVE,0,
                new MediaMetadata(f.fileName(),f.mediaType(),f.size(),f.sourceSha256(),null,null,null));
        return new MediaUploadResultSnapshot(MediaUploadTargetType.MUSIC_TRACK,op.intent().target().id(),version,"历史歌","",null,true,0,null,resource,null,null,null,NOW,NOW);
    }
    static boolean claim(SqlSessionFactory factory,UUID id,CountDownLatch waiting,CountDownLatch start) throws Exception {
        try(var session=factory.openSession(false)) {waiting.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));var result=repo(session).claimContent(1,id,UUID.randomUUID());session.commit();return result.isPresent();}
    }
    static String fingerprint(MediaPostgresTestSupport.ScopedSchema schema) throws Exception {
        try(var connection=schema.openConnection();var statement=connection.createStatement();var row=statement.executeQuery("SELECT md5(coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY id),'')) FROM media_upload_operation t")){row.next();return row.getString(1);}
    }
}
