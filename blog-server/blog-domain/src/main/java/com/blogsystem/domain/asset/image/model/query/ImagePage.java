package com.blogsystem.domain.asset.image.model.query;

import com.blogsystem.domain.asset.image.model.valobj.StoredImage;

import java.util.List;

public record ImagePage(List<StoredImage> images, long total) {
    public ImagePage {
        images = List.copyOf(images);
    }
}
