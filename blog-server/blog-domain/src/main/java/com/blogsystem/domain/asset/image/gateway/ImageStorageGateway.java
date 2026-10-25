package com.blogsystem.domain.asset.image.gateway;

import com.blogsystem.domain.asset.image.model.query.ImagePage;
import com.blogsystem.domain.asset.image.model.valobj.ImagePath;
import com.blogsystem.domain.asset.image.model.valobj.ImageUpload;
import com.blogsystem.domain.asset.image.model.valobj.StoredImage;

/**
 * Business storage port. Implementations must isolate the blog's managed image namespace.
 */
public interface ImageStorageGateway {
    StoredImage upload(ImageUpload image, long authenticatedUserId);

    ImagePage list(int offset, int count, String search);

    void delete(ImagePath path);
}
