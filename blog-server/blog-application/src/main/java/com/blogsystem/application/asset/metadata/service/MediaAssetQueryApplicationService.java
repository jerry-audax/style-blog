package com.blogsystem.application.asset.metadata.service;

import com.blogsystem.application.asset.metadata.result.MediaAssetProjection;
import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetReadException;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Read-only cross-context facade; preserves unknown metadata and inactive resource identity. */
public final class MediaAssetQueryApplicationService {
    private final MediaAssetRepository assets;
    private final MediaPublicUrlGateway urls;

    public MediaAssetQueryApplicationService(
            MediaAssetRepository assets, MediaPublicUrlGateway urls) {
        this.assets = Objects.requireNonNull(assets);
        this.urls = Objects.requireNonNull(urls);
    }

    public Map<UUID, MediaAssetProjection> findByIds(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            List<UUID> requested = List.copyOf(ids);
            Map<UUID, MediaAssetProjection> result = new HashMap<>();
            for (int offset = 0; offset < requested.size(); offset += 1000) {
                Set<UUID> batch =
                        Set.copyOf(
                                requested.subList(
                                        offset, Math.min(offset + 1000, requested.size())));
                for (var asset : assets.findByIds(batch)) {
                    if (!batch.contains(asset.id())) throw new MediaAssetReadException();
                    result.put(
                            asset.id(),
                            new MediaAssetProjection(
                                    asset.id(),
                                    asset.kind(),
                                    asset.status(),
                                    asset.objectKey().value(),
                                    urls.publicUrl(asset.provider(), asset.objectKey()),
                                    asset.metadata().mimeType(),
                                    asset.metadata().fileSize()));
                }
            }
            return Map.copyOf(result);
        } catch (RuntimeException failure) {
            throw new MediaAssetReadException();
        }
    }
}
