package com.blogsystem.domain.asset.image.model.query;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

/** A scoped read of registered images; search text is literal, not a SQL wildcard. */
public record ImageMetadataQuery(String provider, MediaObjectKey root, String search, int offset, int limit) {
    public ImageMetadataQuery {
        provider = MediaUploadPolicy.text(provider, 32, false);
        if (root == null || search == null || search.length() > 100
                || search.chars().anyMatch(Character::isISOControl)
                || offset < 0 || offset > 100000 || limit < 1 || limit > 50)
            throw new IllegalArgumentException("Invalid image metadata query");
    }
}
