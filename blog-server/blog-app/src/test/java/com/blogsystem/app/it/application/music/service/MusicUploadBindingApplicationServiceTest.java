package com.blogsystem.app.it.application.music.service;

import com.blogsystem.application.music.service.MusicUploadBindingApplicationService;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicTrackMetadata;
import com.blogsystem.domain.music.model.MusicTrackSnapshot;
import com.blogsystem.domain.music.model.PersistedMusicTrack;

import com.blogsystem.app.it.application.music.service.PersistentMusicCatalogApplicationServiceTest.FakeRepo;
import java.util.Objects;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException.Reason;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import com.blogsystem.app.it.application.asset.metadata.service.MediaCatalogTestAssets;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class MusicUploadBindingApplicationServiceTest {
    final MediaCatalogTestAssets assets=new MediaCatalogTestAssets();
    final BindingRepo tracks=new BindingRepo();
    static final Instant NOW=Instant.ofEpochSecond(1);
    static final String ID="  Existing-视频-ID  ";
    MusicUploadBindingApplicationService service(){return new MusicUploadBindingApplicationService(tracks,assets);}
    MusicTrackSnapshot seed(){
        var oldAudio=assets.add(MediaKind.AUDIO,MediaAssetStatus.ACTIVE,null,null);
        var oldCover=assets.add(MediaKind.IMAGE,MediaAssetStatus.ACTIVE,null,null);
        var row=new MusicTrackSnapshot(new PersistedMusicTrack(ID,"曲名","歌手","123",oldAudio,oldCover,null,"https://example.test/artwork.png",false,12),0,Instant.EPOCH,Instant.EPOCH);
        tracks.rows.put(ID,row);return row;
    }
    MediaAsset uploaded(UUID operationId){
        var id=UUID.randomUUID();var asset=MediaAsset.register(id,"cloudflare_imgbed",new MediaObjectKey("blog/managed-uploads/"+operationId+"/a.mp3"),
                MediaKind.AUDIO,new MediaMetadata("a.mp3","audio/mpeg",1L,"b".repeat(64),null,null,null),NOW);assets.rows.put(id,asset);return asset;
    }
    MediaUploadRequest request(UUID id,Long version){return new MediaUploadRequest(id,MediaUploadTargetType.MUSIC_TRACK,ID,MediaUploadSlot.AUDIO,version,new MediaUploadFile("a.mp3","audio/mpeg",1,"b".repeat(64)),null);}
    MediaUploadResourceSnapshot snapshot(MediaAsset a){return new MediaUploadResourceSnapshot(a.id(),a.provider(),a.objectKey(),a.kind(),a.status(),a.version(),a.metadata());}

    @Test void bindsOnlyRequestedSlotAtVersionZero(){
        var before=seed();UUID op=UUID.randomUUID();var asset=uploaded(op);var req=request(op,0L);
        var target=service().prepareTarget(req,asset.id());assertEquals(before.track().audioAssetId(),target.expectedOldAssetId());
        var intent=MediaUploadPolicy.ready(1,req,target,"cloudflare_imgbed",new MediaObjectKey("blog"),asset.id(),NOW).intent();
        var result=(com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot)service().prepareCommit(intent).commit(snapshot(asset),NOW);
        var after=tracks.rows.get(ID);
        assertEquals(1,after.version());assertEquals(asset.id(),after.track().audioAssetId());
        assertEquals(before.track().coverAssetId(),after.track().coverAssetId());assertNull(after.track().lyricsAssetId());
        assertEquals("曲名",result.title());assertEquals("歌手",result.artist());assertEquals("123",result.sourceId());
        assertFalse(result.enabled());assertEquals(12,result.sortOrder());assertEquals(Instant.EPOCH,result.createdAt());
        assertNull(result.cover().metadata().fileSize());assertNull(result.cover().metadata().mimeType());assertEquals(1,result.targetVersion());
    }
    @Test void missingStaleAndMissingTargetNeverWrites(){
        seed();assertEquals(MediaUploadException.Reason.MISSING_VERSION,assertThrows(MediaUploadException.class,()->service().prepareTarget(request(UUID.randomUUID(),null),UUID.randomUUID())).reason());
        assertEquals(MediaUploadException.Reason.CONFLICT,assertThrows(MediaUploadException.class,()->service().prepareTarget(request(UUID.randomUUID(),2L),UUID.randomUUID())).reason());
        tracks.rows.clear();assertEquals(MediaUploadException.Reason.NOT_FOUND,assertThrows(MediaUploadException.class,()->service().prepareTarget(request(UUID.randomUUID(),0L),UUID.randomUUID())).reason());assertEquals(0,tracks.writes);
    }
    @Test void concurrentMetadataEditCannotBeOverwritten(){
        seed();UUID op=UUID.randomUUID();var asset=uploaded(op);var req=request(op,0L);var target=service().prepareTarget(req,asset.id());
        var prepared=service().prepareCommit(MediaUploadPolicy.ready(1,req,target,"cloudflare_imgbed",new MediaObjectKey("blog"),asset.id(),NOW).intent());
        tracks.updateMetadata(ID,new MusicTrackMetadata("并发编辑","歌手","123",false,12),0,NOW);
        assertEquals(MediaUploadException.Reason.CONFLICT,assertThrows(MediaUploadException.class,()->prepared.commit(snapshot(asset),NOW)).reason());
        assertEquals("并发编辑",tracks.rows.get(ID).track().title());assertNotEquals(asset.id(),tracks.rows.get(ID).track().audioAssetId());
    }
    @Test void wrongKindOrInactiveAssetNeverBinds(){
        var before=seed();UUID op=UUID.randomUUID();var asset=uploaded(op);var req=request(op,0L);var target=service().prepareTarget(req,asset.id());
        var prepared=service().prepareCommit(MediaUploadPolicy.ready(1,req,target,"cloudflare_imgbed",new MediaObjectKey("blog"),asset.id(),NOW).intent());
        for(var status:List.of(MediaAssetStatus.DELETE_PENDING,MediaAssetStatus.DELETED)) {
            var inactive=MediaAsset.restore(asset.id(),asset.provider(),asset.objectKey(),asset.kind(),asset.metadata(),status,0,NOW,NOW);
            assets.rows.put(asset.id(),inactive);assertThrows(MediaUploadException.class,()->prepared.commit(snapshot(inactive),NOW));
        }
        var wrong=MediaAsset.restore(asset.id(),asset.provider(),asset.objectKey(),MediaKind.IMAGE,asset.metadata(),MediaAssetStatus.ACTIVE,0,NOW,NOW);
        assets.rows.put(asset.id(),wrong);assertThrows(MediaUploadException.class,()->prepared.commit(snapshot(wrong),NOW));
        assertEquals(before,tracks.rows.get(ID));assertEquals(0,tracks.writes);
    }
    static class BindingRepo extends PersistentMusicCatalogApplicationServiceTest.FakeRepo {
        @Override public MusicTrackSnapshot bindMedia(String id,MusicMediaKind kind,UUID asset,long version,UUID oldAsset,Instant now){
            var row=rows.get(id);if(row==null)throw new MusicCatalogException(MusicCatalogException.Reason.NOT_FOUND);
            var t=row.track();UUID actual=switch(kind){case AUDIO->t.audioAssetId();case COVER->t.coverAssetId();case LYRICS->t.lyricsAssetId();};
            if(row.version()!=version || !Objects.equals(actual,oldAsset))throw new MusicCatalogException(MusicCatalogException.Reason.CONFLICT);
            writes++;var result=new MusicTrackSnapshot(new PersistedMusicTrack(id,t.title(),t.artist(),t.sourceId(),kind==MusicMediaKind.AUDIO?asset:t.audioAssetId(),
                    kind==MusicMediaKind.COVER?asset:t.coverAssetId(),kind==MusicMediaKind.LYRICS?asset:t.lyricsAssetId(),t.externalCoverUrl(),t.enabled(),t.sortOrder()),version+1,row.createdAt(),now);rows.put(id,result);return result;
        }
    }
}
