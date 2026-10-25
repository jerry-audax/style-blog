package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import java.time.Instant;

/** Committed image metadata; identity/version come exclusively from the resource. */
public record ImageUploadResultSnapshot(
        MediaUploadResourceSnapshot resource,
        ImagePurpose purpose,
        Instant createdAt,
        Instant updatedAt) implements MediaUploadResultFacts {
    public ImageUploadResultSnapshot {
        if (resource == null || resource.kind() != MediaKind.IMAGE
                || resource.status() != MediaAssetStatus.ACTIVE || purpose == null)
            throw new IllegalArgumentException("Invalid committed image facts");
        createdAt = MediaUploadPolicy.precision(createdAt);
        updatedAt = MediaUploadPolicy.precision(updatedAt);
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("Invalid image timestamps");
    }

    @Override public MediaUploadTargetType targetType() { return MediaUploadTargetType.IMAGE_CREATE; }
    @Override public String targetId() { return resource.assetId().toString(); }
    @Override public long targetVersion() { return resource.assetVersion(); }
}
