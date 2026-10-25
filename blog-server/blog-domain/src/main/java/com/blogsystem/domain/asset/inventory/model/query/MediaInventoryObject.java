package com.blogsystem.domain.asset.inventory.model.query;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.util.Objects;

/** A sanitized provider listing entry used by the import preview. */
public record MediaInventoryObject(
        MediaKind kind, MediaObjectKey objectKey, MediaMetadata metadata) {
    public MediaInventoryObject {
        Objects.requireNonNull(kind, "资源类型不能为空");
        Objects.requireNonNull(objectKey, "资源路径不能为空");
        Objects.requireNonNull(metadata, "资源元数据不能为空");
    }
}
