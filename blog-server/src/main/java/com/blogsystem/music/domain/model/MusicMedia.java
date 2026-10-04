package com.blogsystem.music.domain.model;

public record MusicMedia(String path, String url, String mediaType, long size) {
    public MusicMedia {
        if (path == null || path.isBlank() || url == null || url.isBlank() || mediaType == null || mediaType.isBlank() || size < 0)
            throw new IllegalArgumentException("Invalid music media");
    }
}
