package com.blogsystem.it.media;

import com.blogsystem.application.asset.deletion.command.MediaDeletionCommand;
import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionTarget;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.ItemStatus;
import com.blogsystem.domain.music.model.MusicMediaKind;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaDeletionWorkflowIntegrationTest {
    static final String SONG="  Existing-视频-ID  ";
    static final MediaDeletionTarget TRACK=new MediaDeletionTarget(MediaDeletionTarget.Kind.MUSIC_TRACK,SONG,null);
    static MediaDeletionTarget media(MediaDeletionTarget.Slot slot){return new MediaDeletionTarget(MediaDeletionTarget.Kind.MUSIC_MEDIA,SONG,slot);}
    static MediaDeletionCommand command(MediaDeletionWorkflowFixture f,MediaDeletionTarget target){
        var preview=f.service.preview(7,target);
        Map<UUID,Long> versions=new HashMap<>();preview.candidates().forEach(c->versions.put(c.assetId(),c.assetVersion()));
        assertFalse(preview.referenceCoverageComplete());return new MediaDeletionCommand(target,preview.expectedVersion(),versions);
    }
    @Test void deletesTrackOnlyLocallyAndReplaysWithoutLookingUpDeletedTarget() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            var cmd=command(f,TRACK);String key=UUID.randomUUID().toString();var result=f.service.submit(7,key,cmd);
            assertTrue(result.processingComplete());assertFalse(result.allFilesDeleted());assertEquals(2,result.items().size());
            assertTrue(result.items().stream().allMatch(i->i.status()==ItemStatus.RETAINED));
            assertTrue(f.music.findPublicCandidates(20).isEmpty());assertTrue(f.music.findSnapshotById(SONG).isEmpty());
            assertEquals(8,f.jdbc.queryForObject("SELECT version FROM music_track WHERE id=?",Long.class,SONG));
            assertNotNull(f.jdbc.queryForObject("SELECT audio_asset_id FROM music_track WHERE id=?",UUID.class,SONG));
            assertEquals(result,f.service.submit(7,key,cmd));assertEquals(1,f.service.history(7,0,20).total());
            assertThrows(MediaDeletionException.class,()->f.service.get(result.id(),8));
            assertEquals(3,f.jdbc.queryForObject("SELECT count(*) FROM media_asset WHERE status='ACTIVE'",Integer.class));
        }
    }
    @Test void removingOnlyExternalCoverCreatesAnEmptyReceiptAndClearsFallback() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            f.jdbc.update("UPDATE music_track SET cover_asset_id=NULL,external_cover_url='https://example.test/cover.jpg'");
            var result=f.service.submit(7,UUID.randomUUID().toString(),command(f,media(MediaDeletionTarget.Slot.COVER)));
            var saved=f.music.findSnapshotById(SONG).orElseThrow();assertEquals(8,saved.version());
            assertNull(saved.track().coverAssetId());assertNull(saved.track().externalCoverUrl());
            assertTrue(result.items().isEmpty());assertTrue(result.processingComplete());assertFalse(result.allFilesDeleted());
        }
    }
    @Test void removingAudioLeavesMetadataButRemovesPublicTrack() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            f.service.submit(7,UUID.randomUUID().toString(),command(f,media(MediaDeletionTarget.Slot.AUDIO)));
            var saved=f.music.findSnapshotById(SONG).orElseThrow();assertEquals("历史歌",saved.track().title());
            assertNull(saved.track().audioAssetId());assertNotNull(saved.track().coverAssetId());assertTrue(f.music.findPublicCandidates(20).isEmpty());
        }
    }
    @Test void videoSoftDeleteRetainsNonNullAssetForeignKey() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            var target=new MediaDeletionTarget(MediaDeletionTarget.Kind.SURPRISE_VIDEO,"video-1",null);
            var receipt=f.service.submit(7,UUID.randomUUID().toString(),command(f,target));
            assertEquals(1,receipt.items().size());assertTrue(f.videos.findPublicCandidates().isEmpty());
            assertNotNull(f.jdbc.queryForObject("SELECT video_asset_id FROM surprise_video WHERE id='video-1'",UUID.class));
        }
    }
    @Test void repeatedAssetSlotsAreDeduplicated() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            f.jdbc.update("UPDATE music_track SET cover_asset_id=audio_asset_id,lyrics_asset_id=audio_asset_id");
            var result=f.service.submit(7,UUID.randomUUID().toString(),command(f,TRACK));assertEquals(1,result.items().size());
        }
    }
    @Test void staleVersionsMissingAssetsAndDifferentCommandsCannotMutateTarget() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            var old=command(f,TRACK);f.jdbc.update("UPDATE music_track SET version=version+1");
            assertEquals(MediaDeletionException.Reason.CONFLICT,assertThrows(MediaDeletionException.class,()->f.service.submit(7,UUID.randomUUID().toString(),old)).reason());
            var current=command(f,TRACK);
            assertThrows(MediaDeletionException.class,()->f.service.submit(7,UUID.randomUUID().toString(),new MediaDeletionCommand(TRACK,current.expectedVersion(),Map.of())));
            f.jdbc.update("UPDATE media_asset SET version=version+1 WHERE id=?",UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO));
            assertEquals(MediaDeletionException.Reason.CONFLICT,assertThrows(MediaDeletionException.class,()->f.service.submit(7,UUID.randomUUID().toString(),current)).reason());
            var refreshed=command(f,TRACK);
            String key=UUID.randomUUID().toString();f.service.submit(7,key,refreshed);
            assertEquals(MediaDeletionException.Reason.CONFLICT,assertThrows(MediaDeletionException.class,()->f.service.submit(7,key,new MediaDeletionCommand(TRACK,99,refreshed.assetVersions()))).reason());
            assertEquals(1,f.service.history(7,0,20).total());
        }
    }
    @Test void itemInsertFailureRollsBackBusinessAndRoot() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            f.jdbc.execute("ALTER TABLE media_delete_item ADD CONSTRAINT test_reject_item CHECK(false)");
            assertEquals(MediaDeletionException.Reason.UNAVAILABLE,assertThrows(MediaDeletionException.class,()->f.service.submit(7,UUID.randomUUID().toString(),command(f,TRACK))).reason());
            assertEquals(7,f.music.findSnapshotById(SONG).orElseThrow().version());assertEquals(0,f.service.history(7,0,20).total());
            assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM media_delete_item",Integer.class));
        }
    }
    @Test void concurrentSameKeyReturnsExactlyOneReceipt() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            var cmd=command(f,TRACK);String key=UUID.randomUUID().toString();var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            Callable<UUID> task=()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return f.service.submit(7,key,cmd).id();};
            try{var a=pool.submit(task);var b=pool.submit(task);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(1,f.service.history(7,0,20).total());}
            finally{go.countDown();pool.shutdownNow();}
        }
    }
    @Test void bindingAndDeleteHaveExactlyOneVersionWinner() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            UUID replacement=UUID.randomUUID();f.jdbc.update("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES (?,'cloudflare_imgbed','blog/music/replacement.mp3','AUDIO','replacement.mp3','ACTIVE',now(),now())",replacement);
            var cmd=command(f,TRACK);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            Callable<Boolean> delete=()->{ready.countDown();go.await(5,TimeUnit.SECONDS);try{f.service.submit(7,UUID.randomUUID().toString(),cmd);return true;}catch(MediaDeletionException e){assertEquals(MediaDeletionException.Reason.CONFLICT,e.reason());return false;}};
            Callable<Boolean> bind=()->{ready.countDown();go.await(5,TimeUnit.SECONDS);try{f.tx.execute(s->f.music.bindMedia(SONG,MusicMediaKind.AUDIO,replacement,7,UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO),Instant.now()));return true;}catch(com.blogsystem.domain.music.model.MusicCatalogException e){return false;}};
            try{var a=pool.submit(delete);var b=pool.submit(bind);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();boolean removed=a.get(15,TimeUnit.SECONDS),bound=b.get(15,TimeUnit.SECONDS);assertTrue(removed^bound);if(bound)assertEquals(replacement,f.music.findSnapshotById(SONG).orElseThrow().track().audioAssetId());}
            finally{go.countDown();pool.shutdownNow();}
        }
    }
}
