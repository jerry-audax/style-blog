package com.blogsystem.asset.domain.repository;

import com.blogsystem.asset.domain.model.*;

/**
 * Business storage port. Implementations must isolate the blog's managed image namespace.
 */
public interface ImageRepository {
    StoredImage upload(ImageUpload image, long authenticatedUserId);

    ImagePage list(int offset, int count, String search);

    void delete(ImagePath path);
}
