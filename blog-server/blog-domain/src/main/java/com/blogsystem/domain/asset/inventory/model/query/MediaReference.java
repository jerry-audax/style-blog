package com.blogsystem.domain.asset.inventory.model.query;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.util.Objects;

/** A business-side reference to a provider object used by a dry-run reconciliation. */
public record MediaReference(
        MediaKind kind, MediaObjectKey objectKey, String ownerType, String ownerId, String label) {

    public MediaReference {
        Objects.requireNonNull(kind, "引用资源类型不能为空");
        Objects.requireNonNull(objectKey, "引用资源路径不能为空");
        ownerType = requireText(ownerType, "引用所属类型不能为空");
        ownerId = requireText(ownerId, "引用所属标识不能为空");
        label = requireText(label, "引用标签不能为空");
        if (ownerType.length() > 64 || ownerId.length() > 128 || label.length() > 255) {
            throw new IllegalArgumentException("媒体引用字段过长");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
