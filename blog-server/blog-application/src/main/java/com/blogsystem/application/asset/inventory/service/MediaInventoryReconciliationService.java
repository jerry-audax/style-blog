package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.asset.inventory.result.MediaReconciliationReport;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryPreview;
import com.blogsystem.domain.asset.inventory.model.query.MediaReconciliationEntry;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaReconciliationStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Performs a read-only comparison between provider inventory and business references.
 * It deliberately has no persistence or provider side effects.
 */
@Service
public final class MediaInventoryReconciliationService {

    public MediaReconciliationReport reconcile(
            MediaInventoryPreview inventory, List<MediaReference> references) {
        Objects.requireNonNull(inventory, "盘点结果不能为空");
        Objects.requireNonNull(references, "业务引用不能为空");

        Map<String, Set<MediaKind>> inventoryKindsByPath = inventoryKindsByPath(inventory);
        Map<String, Set<MediaKind>> referenceKindsByPath = referenceKindsByPath(references);
        Set<String> referencedPaths = referenceKindsByPath.keySet();
        List<MediaReconciliationEntry> entries = new ArrayList<>();

        for (MediaReference reference : references) {
            String path = reference.objectKey().value();
            Set<MediaKind> referenceKinds = referenceKindsByPath.get(path);
            Set<MediaKind> inventoryKinds = inventoryKindsByPath.getOrDefault(path, Set.of());
            boolean kindConflict =
                    referenceKinds.size() > 1
                            || inventoryKinds.size() > 1
                            || (!inventoryKinds.isEmpty()
                                    && !inventoryKinds.contains(reference.kind()));
            MediaReconciliationStatus status;
            if (kindConflict) {
                status = MediaReconciliationStatus.CONFLICT;
            } else if (inventoryKinds.contains(reference.kind())) {
                status = MediaReconciliationStatus.MATCHED;
            } else {
                status = MediaReconciliationStatus.MISSING;
            }
            entries.add(entry(status, reference));
        }

        for (Map.Entry<String, Set<MediaKind>> inventoryEntry : inventoryKindsByPath.entrySet()) {
            if (referencedPaths.contains(inventoryEntry.getKey())) {
                continue;
            }
            List<MediaKind> kinds = inventoryEntry.getValue().stream().sorted().toList();
            for (MediaKind kind : kinds) {
                MediaInventoryObject object = findObject(inventory, kind, inventoryEntry.getKey());
                entries.add(
                        new MediaReconciliationEntry(
                                kinds.size() > 1
                                        ? MediaReconciliationStatus.CONFLICT
                                        : MediaReconciliationStatus.NEW,
                                kind,
                                object.objectKey(),
                                "provider",
                                inventoryEntry.getKey(),
                                object.metadata().fileName()));
            }
        }

        return new MediaReconciliationReport(entries);
    }

    private static Map<String, Set<MediaKind>> inventoryKindsByPath(
            MediaInventoryPreview inventory) {
        Map<String, Set<MediaKind>> result = new HashMap<>();
        inventory
                .byKind()
                .forEach(
                        (kind, objects) -> {
                            Objects.requireNonNull(kind, "盘点资源类型不能为空");
                            Objects.requireNonNull(objects, "盘点资源列表不能为空");
                            for (MediaInventoryObject object : objects) {
                                Objects.requireNonNull(object, "盘点资源明细不能为空");
                                boolean added =
                                        result.computeIfAbsent(
                                                        object.objectKey().value(),
                                                        ignored -> new HashSet<>())
                                                .add(kind);
                                if (!added) throw new MediaInventoryException("盘点明细包含同类型的重复对象");
                            }
                        });
        return result;
    }

    private static Map<String, Set<MediaKind>> referenceKindsByPath(
            List<MediaReference> references) {
        Map<String, Set<MediaKind>> result = new HashMap<>();
        for (MediaReference reference : references) {
            Objects.requireNonNull(reference, "业务引用明细不能为空");
            result.computeIfAbsent(reference.objectKey().value(), ignored -> new HashSet<>())
                    .add(reference.kind());
        }
        return result;
    }

    private static MediaInventoryObject findObject(
            MediaInventoryPreview inventory, MediaKind kind, String objectKey) {
        return inventory.byKind().getOrDefault(kind, List.of()).stream()
                .filter(object -> object.objectKey().value().equals(objectKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("盘点明细索引不一致"));
    }

    private static MediaReconciliationEntry entry(
            MediaReconciliationStatus status, MediaReference reference) {
        return new MediaReconciliationEntry(
                status,
                reference.kind(),
                reference.objectKey(),
                reference.ownerType(),
                reference.ownerId(),
                reference.label());
    }
}
