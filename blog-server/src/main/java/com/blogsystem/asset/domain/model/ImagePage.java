package com.blogsystem.asset.domain.model;

import java.util.List;

public record ImagePage(List<StoredImage> images, long total) {
    public ImagePage {
        images = List.copyOf(images);
    }
}
