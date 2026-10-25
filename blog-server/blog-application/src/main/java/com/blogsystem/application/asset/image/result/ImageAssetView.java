package com.blogsystem.application.asset.image.result;

import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** Owner-facing metadata, with exact decimal versions and no storage keys/provider fields. */
public record ImageAssetView(UUID assetId, String assetVersion, String fileName, String url,
        String mimeType, Long fileSize, MediaAssetStatus status, Instant createdAt, Instant updatedAt) {
    public static ImageAssetView from(MediaUploadResourceSnapshot resource, Instant createdAt,
            Instant updatedAt, MediaPublicUrlGateway urls) {
        if (resource.kind() != MediaKind.IMAGE || resource.status() != MediaAssetStatus.ACTIVE)
            throw new IllegalArgumentException("Image is not available");
        String url = urls.publicUrl(resource.provider(), resource.objectKey());
        URI uri = URI.create(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Invalid public image URL");
        return new ImageAssetView(resource.assetId(), Long.toString(resource.assetVersion()), resource.metadata().fileName(),
                url, resource.metadata().mimeType(), resource.metadata().fileSize(), resource.status(), createdAt, updatedAt);
    }
}
