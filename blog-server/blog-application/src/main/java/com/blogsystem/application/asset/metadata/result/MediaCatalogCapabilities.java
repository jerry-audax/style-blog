package com.blogsystem.application.asset.metadata.result;

import com.blogsystem.application.asset.upload.result.MediaUploadLimits;
import com.blogsystem.application.asset.image.result.ImageCatalogCapabilities;

/** Separate image/upload/deletion protocols; absent image capabilities mean unknown support. */
public record MediaCatalogCapabilities(
        boolean metadataWrite,
        boolean metadataVersionRequired,
        boolean mediaUpload,
        boolean mediaReplace,
        boolean mediaDelete,
        boolean catalogDelete,
        String uploadProtocol,
        MediaUploadLimits uploadLimits,
        String deleteProtocol,
        ImageCatalogCapabilities images) {
    public MediaCatalogCapabilities(boolean metadataWrite, boolean metadataVersionRequired, boolean mediaUpload,
            boolean mediaReplace, boolean mediaDelete, boolean catalogDelete, String uploadProtocol,
            MediaUploadLimits uploadLimits, String deleteProtocol) {
        this(metadataWrite, metadataVersionRequired, mediaUpload, mediaReplace, mediaDelete, catalogDelete,
                uploadProtocol, uploadLimits, deleteProtocol, null);
    }

    public MediaCatalogCapabilities withImages(ImageCatalogCapabilities value) {
        return new MediaCatalogCapabilities(metadataWrite, metadataVersionRequired, mediaUpload, mediaReplace,
                mediaDelete, catalogDelete, uploadProtocol, uploadLimits, deleteProtocol, value);
    }
    public MediaCatalogCapabilities(
            boolean metadataWrite,
            boolean metadataVersionRequired,
            boolean mediaUpload,
            boolean mediaReplace,
            boolean mediaDelete,
            boolean catalogDelete,
            String uploadProtocol,
            MediaUploadLimits uploadLimits) {
        this(
                metadataWrite,
                metadataVersionRequired,
                mediaUpload,
                mediaReplace,
                mediaDelete,
                catalogDelete,
                uploadProtocol,
                uploadLimits,
                null);
    }

    public MediaCatalogCapabilities withTrackedDeletion(boolean enabled) {
        return new MediaCatalogCapabilities(
                metadataWrite,
                metadataVersionRequired,
                mediaUpload,
                mediaReplace,
                enabled,
                enabled,
                uploadProtocol,
                uploadLimits,
                "tracked-delete-v1",
                images);
    }

    public MediaCatalogCapabilities(
            boolean metadataWrite,
            boolean metadataVersionRequired,
            boolean mediaUpload,
            boolean mediaReplace,
            boolean mediaDelete,
            boolean catalogDelete) {
        this(
                metadataWrite,
                metadataVersionRequired,
                mediaUpload,
                mediaReplace,
                mediaDelete,
                catalogDelete,
                null,
                null);
    }
}
