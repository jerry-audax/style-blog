package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.asset.inventory.service.MediaAssetImportApplicationService;

import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetPage;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetQuery;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;

import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class MediaAssetImportApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    @Test void repeatedImportPreservesIdentityAndEditedMetadata() {
        var repository = new MemoryRepository();
        var service = new MediaAssetImportApplicationService(repository);
        var first = service.registerExisting("cloudflare_imgbed", "blog", List.of(object(MediaKind.AUDIO,"blog/music/a.mp3",null)), NOW);
        var second = service.registerExisting("cloudflare_imgbed", "blog", List.of(object(MediaKind.AUDIO,"blog/music/a.mp3",10L)), NOW);
        assertEquals(1, first.created());
        assertEquals(0, second.created());
        assertEquals(1, second.preserved());
        assertEquals(first.assets().get(0).id(), second.assets().get(0).id());
        assertNull(second.assets().get(0).metadata().fileSize());
    }

    @Test void invalidOrDuplicateBatchCannotInsertEarlierValidObjects() {
        var repository = new MemoryRepository();
        var service = new MediaAssetImportApplicationService(repository);
        var valid = object(MediaKind.AUDIO,"blog/music/a.mp3",0L);
        assertThrows(IllegalArgumentException.class, () -> service.registerExisting("cloudflare_imgbed","blog",
                List.of(valid,object(MediaKind.IMAGE,"other/a.png",null)),NOW));
        assertEquals(0, repository.values.size());
        assertThrows(IllegalArgumentException.class, () -> service.registerExisting("cloudflare_imgbed","blog",List.of(valid,valid),NOW));
        assertEquals(0, repository.values.size());
    }

    @Test void mismatchedExistingKindFailsRatherThanOverwriting() {
        var repository = new MemoryRepository();
        var service = new MediaAssetImportApplicationService(repository);
        service.registerExisting("cloudflare_imgbed","blog",List.of(object(MediaKind.IMAGE,"blog/shared/item",null)),NOW);
        assertThrows(IllegalStateException.class, () -> service.registerExisting("cloudflare_imgbed","blog",
                List.of(object(MediaKind.AUDIO,"blog/shared/item",null)),NOW));
        assertEquals(MediaKind.IMAGE,repository.values.get(0).kind());
    }

    private static MediaInventoryObject object(MediaKind kind,String key,Long size) {
        return new MediaInventoryObject(kind,new MediaObjectKey(key),
                new MediaMetadata("sample",null,size,null,null,null,null));
    }
    private static final class MemoryRepository implements MediaAssetRepository {
        final List<MediaAsset> values=new ArrayList<>();
        public List<MediaAsset> findByLocations(String provider,Set<MediaObjectKey> keys) {return values.stream().filter(a->a.provider().equals(provider)&&keys.contains(a.objectKey())).toList();}
        public Optional<MediaAsset> findById(UUID id) {return values.stream().filter(a->a.id().equals(id)).findFirst();}
        public Optional<MediaAsset> findByLocation(String provider,MediaObjectKey key) {return values.stream().filter(a->a.provider().equals(provider)&&a.objectKey().equals(key)).findFirst();}
        public MediaAsset insert(MediaAsset asset) {values.add(asset);return asset;}
        public MediaAsset updateMetadata(MediaAsset asset,long version) {throw new AssertionError("import cannot update");}
        public MediaAssetPage findPage(MediaAssetQuery query) {throw new AssertionError();}
    }
}
