package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.util.Objects;

public record MediaUploadCandidate(
        MediaObjectKey objectKey,
        MediaKind kind,
        Long size,
        String uploadChannel,
        String channelName) {
    public MediaUploadCandidate {
        Objects.requireNonNull(objectKey);
        Objects.requireNonNull(kind);
        if (size == null || size < 0 || uploadChannel == null || channelName == null)
            throw new IllegalArgumentException("Incomplete candidate facts");
    }
}
