package com.blogsystem.domain.asset.image.repository;

import com.blogsystem.domain.asset.image.model.query.ImageMetadataQuery;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetPage;

/** Registered metadata only; never enumerates or mutates the binary provider. */
public interface ImageMetadataRepository {
    MediaAssetPage findPage(ImageMetadataQuery query);
}
