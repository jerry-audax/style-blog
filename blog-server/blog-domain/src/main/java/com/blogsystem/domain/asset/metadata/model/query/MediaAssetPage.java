package com.blogsystem.domain.asset.metadata.model.query;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;

import java.util.List;
import java.util.Objects;

/** A read-only page returned by the media repository. */
public record MediaAssetPage(List<MediaAsset> records, long total, int offset, int limit) {
    public MediaAssetPage {
        records = List.copyOf(Objects.requireNonNull(records, "资源列表不能为空"));
        if (total < 0) {
            throw new IllegalArgumentException("资源总数不能小于零");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("分页偏移量不能小于零");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("分页大小必须在 1 到 100 之间");
        }
        if (records.size() > limit) {
            throw new IllegalArgumentException("资源列表超出分页大小");
        }
    }
}
