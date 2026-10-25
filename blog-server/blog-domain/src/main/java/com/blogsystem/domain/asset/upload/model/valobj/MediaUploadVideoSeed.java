package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

public record MediaUploadVideoSeed(String title, boolean enabled, int sortOrder) {
    public MediaUploadVideoSeed {
        title = MediaUploadPolicy.text(title, 120, true);
        if (sortOrder < 0 || sortOrder > 100000)
            throw new IllegalArgumentException("Invalid video sort order");
    }
}
