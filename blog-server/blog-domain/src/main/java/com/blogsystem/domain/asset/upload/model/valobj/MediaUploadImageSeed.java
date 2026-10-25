package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;

/** Upload purpose only; creating a file does not change an article/profile reference. */
public record MediaUploadImageSeed(ImagePurpose purpose) {
    public MediaUploadImageSeed {
        if (purpose == null) throw new IllegalArgumentException("Image purpose required");
    }

    public MediaUploadSlot slot() {
        return purpose == ImagePurpose.AVATAR ? MediaUploadSlot.AVATAR : MediaUploadSlot.IMAGE;
    }
}
