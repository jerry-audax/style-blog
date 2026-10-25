package com.blogsystem.domain.asset.metadata.model.query;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

/** Optional media filters and a bounded offset page request. */
public record MediaAssetQuery(MediaKind kind, MediaAssetStatus status, int offset, int limit) {
    public MediaAssetQuery {
        if (offset < 0) {
            throw new IllegalArgumentException("分页偏移量不能小于零");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("分页大小必须在 1 到 100 之间");
        }
    }
}
