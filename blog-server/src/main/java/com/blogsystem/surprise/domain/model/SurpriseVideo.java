package com.blogsystem.surprise.domain.model;

/** A playable prank video owned by the blog. */
public record SurpriseVideo(String id, String title, String url, String path,
                            String mediaType, long size, boolean enabled, int sortOrder) {
    public SurpriseVideo {
        if (id == null || id.isBlank() || title == null || title.isBlank()
                || url == null || url.isBlank() || path == null || path.isBlank()
                || mediaType == null || mediaType.isBlank() || size < 0) {
            throw new IllegalArgumentException("整蛊视频资料无效");
        }
    }

    public SurpriseVideo withMetadata(String nextTitle, boolean nextEnabled, int nextSortOrder) {
        return new SurpriseVideo(id, nextTitle, url, path, mediaType, size, nextEnabled, nextSortOrder);
    }
}
