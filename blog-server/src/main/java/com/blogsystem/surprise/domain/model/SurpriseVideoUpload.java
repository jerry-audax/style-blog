package com.blogsystem.surprise.domain.model;

import java.util.Locale;

public record SurpriseVideoUpload(byte[] bytes, String mediaType, String filename) {
    public SurpriseVideoUpload {
        if (bytes == null || bytes.length == 0 || bytes.length > 200 * 1024 * 1024)
            throw new IllegalArgumentException("视频为空或超过 200 MiB");
        if (filename == null || filename.isBlank() || mediaType == null)
            throw new IllegalArgumentException("视频参数无效");
        String mime = mediaType.toLowerCase(Locale.ROOT);
        if (!mime.startsWith("video/")) throw new IllegalArgumentException("仅支持视频文件");
        bytes = bytes.clone();
        mediaType = mime;
        filename = filename.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (filename.isBlank() || filename.length() > 180) throw new IllegalArgumentException("视频文件名无效");
    }
}
