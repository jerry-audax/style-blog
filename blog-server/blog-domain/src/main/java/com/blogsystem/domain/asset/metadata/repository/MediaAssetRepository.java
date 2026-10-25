package com.blogsystem.domain.asset.metadata.repository;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetPage;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetQuery;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port owned by the asset domain. */
public interface MediaAssetRepository {
    Optional<MediaAsset> findById(UUID id);

    /** Compatibility default for existing adapters; database implementations batch this query. */
    default List<MediaAsset> findByIds(Set<UUID> ids) {
        return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
    }

    Optional<MediaAsset> findByLocation(String provider, MediaObjectKey objectKey);

    /** Exact provider/key identities, at most 1000 keys per persistence query. */
    List<MediaAsset> findByLocations(String provider, Set<MediaObjectKey> objectKeys);

    MediaAsset insert(MediaAsset asset);

    MediaAsset updateMetadata(MediaAsset candidate, long expectedVersion);

    MediaAssetPage findPage(MediaAssetQuery query);
}
