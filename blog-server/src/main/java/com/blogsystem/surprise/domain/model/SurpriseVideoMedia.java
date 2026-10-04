package com.blogsystem.surprise.domain.model;

public record SurpriseVideoMedia(String path, String url, String mediaType, long size) {
    public SurpriseVideoMedia {
        if (path == null || path.isBlank() || url == null || url.isBlank() || mediaType == null || mediaType.isBlank() || size < 0)
            throw new IllegalArgumentException("视频存储结果无效");
    }
}
