package com.blogsystem.domain.asset.inventory.model.query;

import com.blogsystem.domain.asset.inventory.model.valobj.MediaReconciliationStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.util.Objects;

/** One immutable dry-run reconciliation result. */
public record MediaReconciliationEntry(
        MediaReconciliationStatus status,
        MediaKind kind,
        MediaObjectKey objectKey,
        String ownerType,
        String ownerId,
        String label) {

    public MediaReconciliationEntry {
        Objects.requireNonNull(status, "对账状态不能为空");
        Objects.requireNonNull(kind, "对账资源类型不能为空");
        Objects.requireNonNull(objectKey, "对账资源路径不能为空");
        ownerType = requireText(ownerType, "对账所属类型不能为空");
        ownerId = requireText(ownerId, "对账所属标识不能为空");
        label = requireText(label, "对账标签不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
