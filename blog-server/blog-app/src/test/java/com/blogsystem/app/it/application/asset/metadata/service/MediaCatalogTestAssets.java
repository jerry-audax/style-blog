package com.blogsystem.app.it.application.asset.metadata.service;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import java.time.Instant;

/** In-memory asset boundary with no provider or database dependency. */
public final class MediaCatalogTestAssets implements MediaAssetRepository {
    public final Map<UUID,MediaAsset> rows=new HashMap<>();
    public int reads;
    public boolean fail;
    public MediaAssetQueryApplicationService query(){return new MediaAssetQueryApplicationService(this,(provider,key)->"https://example.test/file/"+key.value());}
    public UUID add(MediaKind kind,MediaAssetStatus status,Long size,String mime){
        UUID id=UUID.randomUUID();rows.put(id,MediaAsset.restore(id,"cloudflare_imgbed",new MediaObjectKey("blog/media/"+id),kind,
                new MediaMetadata("asset",mime,size,null,null,null,null),status,0,Instant.EPOCH,Instant.EPOCH));return id;
    }
    @Override public List<MediaAsset> findByIds(Set<UUID> ids){reads++;if(fail)throw new MediaAssetReadException();return ids.stream().map(rows::get).filter(Objects::nonNull).toList();}
    @Override public List<MediaAsset> findByLocations(String provider,Set<MediaObjectKey> keys){reads++;if(fail)throw new MediaAssetReadException();return rows.values().stream().filter(a->a.provider().equals(provider)&&keys.contains(a.objectKey())).toList();}
    @Override public Optional<MediaAsset> findById(UUID id){return Optional.ofNullable(rows.get(id));}
    @Override public Optional<MediaAsset> findByLocation(String p,MediaObjectKey k){throw new AssertionError();}
    @Override public MediaAsset insert(MediaAsset asset){throw new AssertionError("Query must not write assets");}
    @Override public MediaAsset updateMetadata(MediaAsset asset,long version){throw new AssertionError("Query must not write assets");}
    @Override public MediaAssetPage findPage(MediaAssetQuery query){throw new AssertionError();}
}
