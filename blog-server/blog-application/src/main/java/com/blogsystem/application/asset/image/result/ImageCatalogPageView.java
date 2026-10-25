package com.blogsystem.application.asset.image.result;

import java.util.List;

public record ImageCatalogPageView(List<ImageAssetView> images, String total, int offset, int count) {
    public ImageCatalogPageView { images = List.copyOf(images); }
}
