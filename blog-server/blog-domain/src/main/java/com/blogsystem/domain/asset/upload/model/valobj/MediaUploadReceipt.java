package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.util.Objects;

public record MediaUploadReceipt(MediaObjectKey objectKey) {
    public MediaUploadReceipt {
        Objects.requireNonNull(objectKey);
    }
}
