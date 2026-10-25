package com.blogsystem.application.asset.image.service;

import com.blogsystem.application.asset.image.result.ImageAssetView;
import com.blogsystem.application.asset.image.result.ImageCatalogPageView;
import com.blogsystem.domain.asset.image.model.query.ImageMetadataQuery;
import com.blogsystem.domain.asset.image.repository.ImageMetadataRepository;
import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetReadException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;

/** Pure database read + public URL projection. No provider listing, upload or registration. */
public final class ImageCatalogQueryApplicationService {
    private final ImageMetadataRepository images;
    private final MediaPublicUrlGateway urls;
    private final String provider;
    private final MediaObjectKey root;

    public ImageCatalogQueryApplicationService(ImageMetadataRepository images, MediaPublicUrlGateway urls,
            String provider, MediaObjectKey root) {
        this.images = images; this.urls = urls; this.provider = provider; this.root = root;
    }

    public ImageCatalogPageView findPage(int offset, int count, String search) {
        var query = new ImageMetadataQuery(provider, root, search, offset, count);
        try {
            var page = images.findPage(query);
            var views = page.records().stream().map(a -> ImageAssetView.from(new MediaUploadResourceSnapshot(
                    a.id(), a.provider(), a.objectKey(), a.kind(), a.status(), a.version(), a.metadata()), a.createdAt(), a.updatedAt(), urls)).toList();
            return new ImageCatalogPageView(views, Long.toString(page.total()), page.offset(), page.limit());
        } catch (RuntimeException failure) {
            throw new MediaAssetReadException();
        }
    }
}
