package com.blogsystem.domain.asset.upload.model.entity;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.query.MediaUploadPage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFingerprint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTransport;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadVideoSeed;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.BeginRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.ChunkConfirmed;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.Pause;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint.Receipt;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException.Reason;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Lease;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Outcome;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Progress;

import java.util.ArrayList;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MediaUploadOperationTest {
    static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000201");
    static final UUID ASSET=UUID.fromString("00000000-0000-0000-0000-000000000202");
    static final String HASH="a".repeat(64);
    static final Instant NOW=Instant.parse("2026-10-14T00:00:00.123456789Z");

    @Test void exactByteLimitsAndLegalTargets() {
        long[] sizes={1,16000000,16000001,32000000,104857600};
        int[] chunks={1,1,2,2,7};
        for(int i=0;i<sizes.length;i++) {
            var op=ready(MediaUploadSlot.AUDIO,sizes[i]);
            assertEquals(chunks[i],op.intent().totalChunks());
            assertEquals(i<2?MediaUploadTransport.DIRECT:MediaUploadTransport.CHUNKED,op.intent().transportMode());
            assertEquals(0L,op.intent().target().expectedVersion());
            assertEquals("  Existing-视频-ID  ",op.intent().target().id());
        }
        assertEquals(14,ready(MediaUploadSlot.VIDEO,209715200).intent().totalChunks());
        assertEquals(1,ready(MediaUploadSlot.COVER,10485760).intent().totalChunks());
        assertEquals(MediaUploadSlot.LYRICS,ready(MediaUploadSlot.LYRICS,524288).intent().target().slot());
        for(var slot:List.of(MediaUploadSlot.AUDIO,MediaUploadSlot.COVER,MediaUploadSlot.LYRICS,MediaUploadSlot.VIDEO)) {
            long maximum=switch(slot){case AUDIO->104857600;case COVER,IMAGE->10485760;case AVATAR->2097152;case LYRICS->524288;case VIDEO->209715200;};
            assertEquals(MediaUploadException.Reason.TOO_LARGE,assertThrows(MediaUploadException.class,
                    ()->ready(slot,maximum+1)).reason());
        }
    }

    @Test void rejectsEmptyFilesUnsafeNamesAndInvalidSourceHash() {
        assertThrows(IllegalArgumentException.class,()->file(MediaUploadSlot.AUDIO,0));
        for(String name:List.of("../song.mp3","a\\song.mp3","a\nsong.mp3"," ")) {
            assertThrows(IllegalArgumentException.class,()->new MediaUploadFile(name,"audio/mpeg",1,HASH));
        }
        assertThrows(IllegalArgumentException.class,()->new MediaUploadFile("song.mp3","audio/mpeg",1,"not-a-hash"));
        assertEquals(HASH,new MediaUploadFile(" song.mp3 "," AUDIO/MPEG ",1,"A".repeat(64)).sourceSha256());
    }

    @Test void requestFingerprintKeepsNullZeroAndFieldBoundaries() {
        var zero=request(MediaUploadSlot.AUDIO,1,0L);
        var missing=request(MediaUploadSlot.AUDIO,1,null);
        assertEquals(MediaUploadFingerprint.forUpload(1,zero),MediaUploadFingerprint.forUpload(1,request(MediaUploadSlot.AUDIO,1,0L)));
        assertNotEquals(MediaUploadFingerprint.forUpload(1,zero),MediaUploadFingerprint.forUpload(1,missing));
        assertNotEquals(MediaUploadFingerprint.forUpload(1,zero),MediaUploadFingerprint.forUpload(2,zero));
        var other=new MediaUploadRequest(ID,MediaUploadTargetType.MUSIC_TRACK,"  Existing-视频-ID  ",MediaUploadSlot.AUDIO,0L,
                new MediaUploadFile("ab.mp3","audio/mpeg",1,HASH),null);
        assertNotEquals(MediaUploadFingerprint.forUpload(1,zero),MediaUploadFingerprint.forUpload(1,other));
        assertNotEquals(MediaUploadFingerprint.forBind(1,ID,ASSET,12,3),MediaUploadFingerprint.forBind(1,ID,ASSET,1,23));
    }

    @Test void frozenIntentUsesDedicatedFolderAndDatabaseTimePrecision() {
        var op=ready(MediaUploadSlot.AUDIO,1);
        assertEquals("blog/managed-uploads/00000000-0000-0000-0000-000000000201",op.intent().operationFolder().value());
        assertEquals(Instant.parse("2026-10-14T00:00:00.123456Z"),op.createdAt());
        assertEquals(MediaUploadStatus.READY,op.progress().status());
        assertNull(op.lease().owner()); assertNull(op.outcome().result());
        assertEquals(0,op.version());
    }

    @Test void illegalStatesAndSnapshotFieldsRejected() {
        var ready=ready(MediaUploadSlot.AUDIO,1);
        assertThrows(IllegalArgumentException.class,()->new MediaUploadOperation(ready.intent(),
                new MediaUploadOperation.Progress(MediaUploadStatus.SUCCEEDED,MediaUploadStage.PERSIST,null,1,null,false,1,0,1),
                new MediaUploadOperation.Lease(null,null,1),new MediaUploadOperation.Outcome(null,null,null,null),1,NOW,NOW));
        assertThrows(IllegalArgumentException.class,()->new MediaUploadOperation.Lease(UUID.randomUUID(),null,1));
        assertThrows(IllegalArgumentException.class,()->new MediaUploadOperation.Progress(MediaUploadStatus.READY,MediaUploadStage.NONE,null,-1,null,false,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new MediaUploadResultSnapshot(MediaUploadTargetType.SURPRISE_CREATE,"video",0,
                "视频",null,null,true,0,null,resource(),null,null,null,NOW,NOW));
    }

    @Test void rejectsWrongMimeSlotAndTargetShape() {
        var audio=file(MediaUploadSlot.AUDIO,1);
        var req=new MediaUploadRequest(ID,MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.COVER,0L,audio,null);
        var target=new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.COVER,0L,null,null);
        assertThrows(IllegalArgumentException.class,()->MediaUploadPolicy.ready(1,req,target,"cloudflare_imgbed",new MediaObjectKey("blog"),ASSET,NOW));
        assertThrows(IllegalArgumentException.class,()->new MediaUploadTarget(MediaUploadTargetType.SURPRISE_CREATE,"video",MediaUploadSlot.VIDEO,0L,null,new MediaUploadVideoSeed("视频",true,0)));
        assertThrows(IllegalArgumentException.class,()->new MediaUploadVideoSeed("视频",true,100001));
    }

    @Test void checkpointsRejectUnconfirmedOrRepeatedChunkAndPauseRaces() {
        var op=ready(MediaUploadSlot.AUDIO,16000001);
        var inFlight=withProgress(op,new MediaUploadOperation.Progress(MediaUploadStatus.UPLOADING,MediaUploadStage.CHUNK,"session-1",0,0,false,1,0,0));
        assertDoesNotThrow(()->MediaUploadPolicy.validateCheckpoint(inFlight,new MediaUploadCheckpoint.ChunkConfirmed(0)));
        assertThrows(MediaUploadException.class,()->MediaUploadPolicy.validateCheckpoint(inFlight,new MediaUploadCheckpoint.ChunkConfirmed(1)));
        assertThrows(MediaUploadException.class,()->MediaUploadPolicy.validateCheckpoint(inFlight,new MediaUploadCheckpoint.Pause()));
        var pausedBoundary=withProgress(op,new MediaUploadOperation.Progress(MediaUploadStatus.UPLOADING,MediaUploadStage.CHUNK,"session-1",1,null,true,1,0,0));
        assertDoesNotThrow(()->MediaUploadPolicy.validateCheckpoint(pausedBoundary,new MediaUploadCheckpoint.Pause()));
        assertThrows(MediaUploadException.class,()->MediaUploadPolicy.validateCheckpoint(pausedBoundary,new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.CHUNK,1)));
        assertThrows(MediaUploadException.class,()->MediaUploadPolicy.validateCheckpoint(pausedBoundary,new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.MERGE,null)));
    }

    @Test void receiptMustStayWithinOriginalOperationFolder() {
        var op=ready(MediaUploadSlot.AUDIO,1);
        var direct=withProgress(op,new MediaUploadOperation.Progress(MediaUploadStatus.UPLOADING,MediaUploadStage.DIRECT,null,0,null,false,1,0,0));
        assertDoesNotThrow(()->MediaUploadPolicy.validateCheckpoint(direct,new MediaUploadCheckpoint.Receipt(resource().objectKey())));
        assertThrows(MediaUploadException.class,()->MediaUploadPolicy.validateCheckpoint(direct,
                new MediaUploadCheckpoint.Receipt(new MediaObjectKey("blog/music/another.mp3"))));
    }

    @Test void explicitBindingKeepsReceiptAndCannotChangeSongOrSlot() {
        var original=ready(MediaUploadSlot.AUDIO,1);
        var source=new MediaUploadOperation(original.intent(),
                new MediaUploadOperation.Progress(MediaUploadStatus.CONFLICT,MediaUploadStage.PERSIST,null,1,null,false,1,0,1),
                new MediaUploadOperation.Lease(null,null,1),new MediaUploadOperation.Outcome(resource().objectKey(),MediaUploadErrorCode.TARGET_CHANGED,null,null),7,NOW,NOW);
        var target=new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"  Existing-视频-ID  ",MediaUploadSlot.AUDIO,8L,null,null);
        var child=MediaUploadPolicy.binding(1,source,UUID.randomUUID(),target,7,NOW);
        assertEquals(ID,child.intent().sourceOperationId());
        assertEquals(ASSET,child.intent().candidateAssetId());
        assertEquals(source.intent().operationFolder(),child.intent().operationFolder());
        assertEquals(source.outcome().confirmedObjectKey(),child.outcome().confirmedObjectKey());
        assertEquals(MediaUploadStatus.UPLOADED,child.progress().status());
        assertEquals(0L,original.intent().target().expectedVersion());
        assertThrows(MediaUploadException.class,()->MediaUploadPolicy.binding(1,source,UUID.randomUUID(),
                new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"other",MediaUploadSlot.AUDIO,8L,null,null),7,NOW));
    }

    @Test void pageDefensivelyCopiesAndKeepsStableCounts() {
        var source=new java.util.ArrayList<>(List.of(ready(MediaUploadSlot.AUDIO,1)));
        var page=new MediaUploadPage(source,5,0,20);source.clear();
        assertEquals(1,page.records().size());assertThrows(UnsupportedOperationException.class,()->page.records().clear());
        assertThrows(IllegalArgumentException.class,()->new MediaUploadPage(List.of(),0,0,101));
    }

    static MediaUploadFile file(MediaUploadSlot slot,long size) {
        return switch(slot){case AUDIO->new MediaUploadFile("歌曲.mp3","audio/mpeg",size,HASH);case COVER,IMAGE,AVATAR->new MediaUploadFile("封面.png","image/png",size,HASH);
            case LYRICS->new MediaUploadFile("歌词.lrc","text/plain",size,HASH);case VIDEO->new MediaUploadFile("视频.mp4","video/mp4",size,HASH);};
    }
    static MediaUploadRequest request(MediaUploadSlot slot,long size,Long version) {
        boolean video=slot==MediaUploadSlot.VIDEO;
        return new MediaUploadRequest(ID,video?MediaUploadTargetType.SURPRISE_CREATE:MediaUploadTargetType.MUSIC_TRACK,
                video?null:"  Existing-视频-ID  ",slot,video?null:version,file(slot,size),video?new MediaUploadVideoSeed("视频",true,0):null);
    }
    static MediaUploadOperation ready(MediaUploadSlot slot,long size) {
        var request=request(slot,size,0L);boolean video=slot==MediaUploadSlot.VIDEO;
        var target=new MediaUploadTarget(request.targetType(),video?"stable-video-id":request.targetId(),slot,request.expectedVersion(),null,request.video());
        return MediaUploadPolicy.ready(1,request,target,"cloudflare_imgbed",new MediaObjectKey("blog"),ASSET,NOW);
    }
    static MediaUploadOperation withProgress(MediaUploadOperation op,MediaUploadOperation.Progress progress) {
        return new MediaUploadOperation(op.intent(),progress,new MediaUploadOperation.Lease(UUID.randomUUID(),NOW.plusSeconds(180),1),op.outcome(),1,NOW,NOW);
    }
    static MediaUploadResourceSnapshot resource() {
        return new MediaUploadResourceSnapshot(ASSET,"cloudflare_imgbed",new MediaObjectKey("blog/managed-uploads/"+ID+"/歌曲+100%.mp3"),
                MediaKind.AUDIO,MediaAssetStatus.ACTIVE,0,new MediaMetadata("歌曲.mp3","audio/mpeg",1L,HASH,null,null,null));
    }
}
