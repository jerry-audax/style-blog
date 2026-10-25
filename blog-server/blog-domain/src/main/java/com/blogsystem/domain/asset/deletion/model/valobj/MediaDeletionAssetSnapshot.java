package com.blogsystem.domain.asset.deletion.model.valobj;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;

import java.util.Objects;

public record MediaDeletionAssetSnapshot(MediaAsset asset, MediaDeletionOwnership ownership) {
    public MediaDeletionAssetSnapshot {
        Objects.requireNonNull(asset);
        Objects.requireNonNull(ownership);
    }
}
