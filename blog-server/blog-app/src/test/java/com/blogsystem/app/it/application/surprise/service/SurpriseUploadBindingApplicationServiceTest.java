package com.blogsystem.app.it.application.surprise.service;

import com.blogsystem.application.surprise.service.SurpriseUploadBindingApplicationService;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadVideoSeed;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.domain.surprise.model.SurpriseCatalogException;
import com.blogsystem.domain.surprise.model.SurpriseVideoSnapshot;

import com.blogsystem.app.it.application.surprise.service.PersistentSurpriseVideoApplicationServiceTest.Repo;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException.Reason;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import com.blogsystem.app.it.application.asset.metadata.service.MediaCatalogTestAssets;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SurpriseUploadBindingApplicationServiceTest {
    final MediaCatalogTestAssets assets=new MediaCatalogTestAssets();final VideoRepo videos=new VideoRepo();final AtomicInteger ids=new AtomicInteger();
    static final Instant NOW=Instant.ofEpochSecond(1);
    SurpriseUploadBindingApplicationService service(){return new SurpriseUploadBindingApplicationService(videos,assets,()->{ids.incrementAndGet();return UUID.fromString("00000000-0000-0000-0000-000000000501");});}
    @Test void videoUsesPreallocatedIdAndDuplicateIsConflict(){
        var service=service();UUID op=UUID.randomUUID();UUID assetId=UUID.randomUUID();
        var req=new MediaUploadRequest(op,MediaUploadTargetType.SURPRISE_CREATE,null,MediaUploadSlot.VIDEO,null,new MediaUploadFile("a.mp4","video/mp4",1,"b".repeat(64)),new MediaUploadVideoSeed("视频",false,17));
        var target=service.prepareTarget(req,assetId);assertEquals(1,ids.get());assertTrue(videos.rows.isEmpty());
        var intent=MediaUploadPolicy.ready(1,req,target,"cloudflare_imgbed",new MediaObjectKey("blog"),assetId,NOW).intent();
        var asset=MediaAsset.register(assetId,"cloudflare_imgbed",new MediaObjectKey("blog/managed-uploads/"+op+"/a.mp4"),MediaKind.VIDEO,new MediaMetadata("a.mp4","video/mp4",1L,"b".repeat(64),null,null,null),NOW);
        assets.rows.put(assetId,asset);var resource=new MediaUploadResourceSnapshot(assetId,asset.provider(),asset.objectKey(),asset.kind(),asset.status(),0,asset.metadata());
        var saved=(com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot)service.prepareCommit(intent).commit(resource,NOW);assertEquals(target.id(),saved.targetId());assertEquals(0,saved.targetVersion());assertFalse(saved.enabled());assertEquals(17,saved.sortOrder());
        assertEquals(MediaUploadException.Reason.CONFLICT,assertThrows(MediaUploadException.class,()->service.prepareCommit(intent).commit(resource,NOW)).reason());
        assertEquals(1,ids.get());assertEquals(1,videos.rows.size());assertEquals(assetId,videos.rows.get(target.id()).video().videoAssetId());
    }
    static class VideoRepo extends PersistentSurpriseVideoApplicationServiceTest.Repo {
        @Override public SurpriseVideoSnapshot insertNewVideo(PersistedSurpriseVideo video,Instant now){
            if(rows.containsKey(video.id()))throw new SurpriseCatalogException(SurpriseCatalogException.Reason.CONFLICT);
            writes++;var result=new SurpriseVideoSnapshot(video,0,now,now);rows.put(video.id(),result);return result;
        }
    }
}
