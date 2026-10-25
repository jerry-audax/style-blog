package com.blogsystem.app.it.application.asset.upload.service;

import com.blogsystem.application.asset.upload.assembler.MediaUploadViewProjector;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.query.MediaUploadPage;
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

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Outcome;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Progress;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class MediaUploadViewProjectorTest {
    @Test void resultUsesOnlyFrozenFactsAndPreservesNullMeasurements(){
        UUID id=UUID.randomUUID(),assetId=UUID.randomUUID();var file=new MediaUploadFile("song.mp3","audio/mpeg",1,"a".repeat(64));
        var ready=MediaUploadPolicy.ready(1,new MediaUploadRequest(id,MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.AUDIO,0L,file,null),new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.AUDIO,0L,null,null),"cloudflare_imgbed",new MediaObjectKey("blog"),assetId,Instant.EPOCH);
        var key=new MediaObjectKey(ready.intent().operationFolder().value()+"/song.mp3");
        var audio=new MediaUploadResourceSnapshot(assetId,"cloudflare_imgbed",key,MediaKind.AUDIO,MediaAssetStatus.ACTIVE,0,new MediaMetadata("song.mp3","audio/mpeg",1L,"a".repeat(64),null,null,null));
        var cover=new MediaUploadResourceSnapshot(UUID.randomUUID(),"cloudflare_imgbed",new MediaObjectKey("blog/old/cover.png"),MediaKind.IMAGE,MediaAssetStatus.DELETE_PENDING,0,new MediaMetadata("cover.png","image/png",null,null,null,null,null));
        var result=new MediaUploadResultSnapshot(MediaUploadTargetType.MUSIC_TRACK,"song",1,"frozen-title","artist",null,true,0,"https://cover.example/a.png",audio,cover,null,null,Instant.EPOCH,Instant.EPOCH);
        var succeeded=new MediaUploadOperation(ready.intent(),new MediaUploadOperation.Progress(MediaUploadStatus.SUCCEEDED,MediaUploadStage.PERSIST,null,1,null,false,1,0,1),ready.lease(),new MediaUploadOperation.Outcome(key,null,result,null),9007199254740993L,Instant.EPOCH,Instant.EPOCH);
        List<String> keys=new ArrayList<>();var projector=new MediaUploadViewProjector((p,k)->{keys.add(k.value());return "https://current.example/file/"+k.value();});
        var view=projector.view(succeeded);assertEquals("9007199254740993",view.operationVersion());assertEquals("frozen-title",view.result().music().title());assertEquals(1L,view.result().music().version());assertEquals("https://cover.example/a.png",view.result().music().cover().url());assertNull(view.result().music().cover().size());assertEquals(List.of(key.value()),keys);assertEquals(1,view.confirmedBytes());
        assertEquals(1,projector.page(new MediaUploadPage(List.of(succeeded),1,0,20)).records().size());
    }
}
