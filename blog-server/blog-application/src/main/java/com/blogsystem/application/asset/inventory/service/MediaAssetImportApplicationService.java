package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Registers already-hosted resources; never uploads, deletes or updates an existing asset. */
public final class MediaAssetImportApplicationService {
    private final MediaAssetRepository repository;

    public MediaAssetImportApplicationService(MediaAssetRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Result registerExisting(
            String provider, String root, List<MediaInventoryObject> objects, Instant now) {
        Objects.requireNonNull(objects);
        var keys = new HashSet<String>();
        List<MediaAsset> candidates = new ArrayList<>();
        for (MediaInventoryObject object : objects) {
            Objects.requireNonNull(object);
            object.objectKey().requireWithin(root);
            if (!keys.add(object.objectKey().value()))
                throw new IllegalArgumentException("导入对象键重复");
            candidates.add(
                    MediaAsset.register(
                            UUID.randomUUID(),
                            provider,
                            object.objectKey(),
                            object.kind(),
                            object.metadata(),
                            now));
        }
        var existing = new HashMap<String, MediaAsset>();
        for (MediaAsset candidate : candidates) {
            repository
                    .findByLocation(provider, candidate.objectKey())
                    .ifPresent(
                            asset -> {
                                if (asset.kind() != candidate.kind())
                                    throw new IllegalStateException("已有资源类型与导入不一致");
                                existing.put(asset.objectKey().value(), asset);
                            });
        }
        List<MediaAsset> assets = new ArrayList<>();
        int created = 0;
        for (MediaAsset candidate : candidates) {
            MediaAsset preserved = existing.get(candidate.objectKey().value());
            if (preserved == null) {
                assets.add(repository.insert(candidate));
                created++;
            } else {
                assets.add(preserved);
            }
        }
        return new Result(created, assets.size() - created, assets);
    }

    public record Result(int created, int preserved, List<MediaAsset> assets) {
        public Result {
            assets = List.copyOf(assets);
        }
    }
}
