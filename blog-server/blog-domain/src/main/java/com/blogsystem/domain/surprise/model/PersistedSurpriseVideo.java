package com.blogsystem.domain.surprise.model;

import java.util.UUID;

/** Existing video metadata bound to a stable media asset identity. */
public record PersistedSurpriseVideo(
        String id, String title, UUID videoAssetId, boolean enabled, int sortOrder) {
    public PersistedSurpriseVideo {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("视频 ID 必须为 1 至 64 个字符且非空白");
        }
        if (title == null || title.isBlank() || title.length() > 120) {
            throw new IllegalArgumentException("视频标题必须为 1 至 120 个字符且非空白");
        }
        if (videoAssetId == null) {
            throw new IllegalArgumentException("视频资源 ID 不能为空");
        }
        if (sortOrder < 0 || sortOrder > 100000) {
            throw new IllegalArgumentException("视频排序必须在 0 至 100000 之间");
        }
    }
}
