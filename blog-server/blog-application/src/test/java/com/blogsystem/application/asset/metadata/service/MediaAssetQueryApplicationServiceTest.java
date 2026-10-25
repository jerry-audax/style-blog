package com.blogsystem.application.asset.metadata.service;

import com.blogsystem.application.asset.metadata.service.MediaAssetQueryApplicationService;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetPage;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetQuery;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetReadException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class MediaAssetQueryApplicationServiceTest {
    @Test void emptyIdsDoNotQuery() {
        var repo=new Fake();var service=service(repo);
        assertTrue(service.findByIds(Set.of()).isEmpty());assertTrue(repo.batchSizes.isEmpty());
    }
    @Test void chunksLargeRequestsWithoutLosingInactiveAssets() {
        var repo=new Fake();
        for(int n=0;n<1001;n++) {UUID id=new UUID(0,n);repo.assets.put(id,asset(id,n==0?MediaAssetStatus.DELETE_PENDING:MediaAssetStatus.ACTIVE,null));}
        var result=service(repo).findByIds(repo.assets.keySet());
        assertEquals(1001,result.size());assertEquals(List.of(1000,1),repo.batchSizes);
        assertEquals(MediaAssetStatus.DELETE_PENDING,result.get(new UUID(0,0)).status());
        assertThrows(UnsupportedOperationException.class,()->result.clear());
    }
    @Test void unknownSizeRemainsNull() {
        var repo=new Fake();UUID unknown=UUID.randomUUID(),zero=UUID.randomUUID();
        repo.assets.put(unknown,asset(unknown,MediaAssetStatus.ACTIVE,null));repo.assets.put(zero,asset(zero,MediaAssetStatus.ACTIVE,0L));
        var result=service(repo).findByIds(Set.of(unknown,zero));
        assertNull(result.get(unknown).size());assertNull(result.get(unknown).mimeType());assertEquals(0L,result.get(zero).size());
    }
    @Test void defaultBatchKeepsOldFakeRepositoriesCompatible() {
        var asset=asset(UUID.randomUUID(),MediaAssetStatus.ACTIVE,null);
        MediaAssetRepository old=new LegacyFake(){@Override public Optional<MediaAsset> findById(UUID id){return Optional.of(asset);}};
        assertEquals(asset.id(),service(old).findByIds(Set.of(asset.id())).get(asset.id()).id());
    }
    @Test void databaseFailuresDoNotBecomeEmptySuccessOrLeakSql() {
        var repo=new Fake(){@Override public List<MediaAsset> findByIds(Set<UUID> ids){throw new IllegalStateException("SELECT private SQL");}};
        var failure=assertThrows(MediaAssetReadException.class,()->service(repo).findByIds(Set.of(UUID.randomUUID())));
        assertFalse(failure.getMessage().contains("SELECT"));
    }
    static MediaAssetQueryApplicationService service(MediaAssetRepository repo){return new MediaAssetQueryApplicationService(repo,(provider,key)->"https://example.test/file/"+key.value());}
    static MediaAsset asset(UUID id,MediaAssetStatus status,Long size){return MediaAsset.restore(id,"cloudflare_imgbed",new MediaObjectKey("blog/music/"+id+".mp3"),MediaKind.AUDIO,
            new MediaMetadata("song.mp3",null,size,null,null,null,null),status,0,Instant.EPOCH,Instant.EPOCH);}
    static class Fake extends LegacyFake {
        final Map<UUID,MediaAsset> assets=new HashMap<>();final List<Integer> batchSizes=new ArrayList<>();
        @Override public List<MediaAsset> findByIds(Set<UUID> ids){batchSizes.add(ids.size());return ids.stream().map(assets::get).filter(Objects::nonNull).toList();}
        @Override public Optional<MediaAsset> findById(UUID id){throw new AssertionError("Must use bulk query");}
    }
    static class LegacyFake implements MediaAssetRepository {
        @Override public List<MediaAsset> findByLocations(String p,Set<MediaObjectKey> keys){throw new AssertionError("Unexpected location lookup");}
        @Override public Optional<MediaAsset> findById(UUID id){throw new AssertionError("Unexpected read");}
        @Override public Optional<MediaAsset> findByLocation(String p,MediaObjectKey k){throw new AssertionError("Unexpected read");}
        @Override public MediaAsset insert(MediaAsset a){throw new AssertionError("GET must not insert");}
        @Override public MediaAsset updateMetadata(MediaAsset a,long v){throw new AssertionError("GET must not update");}
        @Override public MediaAssetPage findPage(MediaAssetQuery q){throw new AssertionError("Unexpected page query");}
    }
}
