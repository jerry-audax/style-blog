package com.blogsystem.domain.asset.inventory.model.query;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable dry-run result; it contains no write or migration decision. */
public record MediaInventoryPreview(Map<MediaKind, List<MediaInventoryObject>> byKind) {
    public MediaInventoryPreview {
        Objects.requireNonNull(byKind, "盘点结果不能为空");
        EnumMap<MediaKind, List<MediaInventoryObject>> copy = new EnumMap<>(MediaKind.class);
        byKind.forEach(
                (kind, objects) -> {
                    MediaKind checkedKind = Objects.requireNonNull(kind, "盘点类型不能为空");
                    List<MediaInventoryObject> checkedObjects =
                            List.copyOf(Objects.requireNonNull(objects, "盘点资源列表不能为空"));
                    if (checkedObjects.stream().anyMatch(object -> object.kind() != checkedKind)) {
                        throw new IllegalArgumentException("盘点类型与资源明细不一致");
                    }
                    copy.put(checkedKind, checkedObjects);
                });
        byKind = Map.copyOf(copy);
    }

    public int count(MediaKind kind) {
        return byKind.getOrDefault(kind, List.of()).size();
    }

    public int total() {
        return byKind.values().stream().mapToInt(List::size).sum();
    }
}
