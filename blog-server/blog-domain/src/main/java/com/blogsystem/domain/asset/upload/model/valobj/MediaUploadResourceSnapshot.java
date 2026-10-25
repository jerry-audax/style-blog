package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import java.util.Objects;
import java.util.UUID;

public record MediaUploadResourceSnapshot(
        UUID assetId,
        String provider,
        MediaObjectKey objectKey,
        MediaKind kind,
        MediaAssetStatus status,
        long assetVersion,
        MediaMetadata metadata) {
    public MediaUploadResourceSnapshot {
        Objects.requireNonNull(assetId);
        Objects.requireNonNull(objectKey);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(status);
        Objects.requireNonNull(metadata);
        provider = MediaUploadPolicy.text(provider, 32, false);
        if (assetVersion < 0) throw new IllegalArgumentException("Invalid asset snapshot version");
    }
}
