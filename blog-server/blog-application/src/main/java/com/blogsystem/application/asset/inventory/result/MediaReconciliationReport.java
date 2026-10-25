package com.blogsystem.application.asset.inventory.result;

import com.blogsystem.domain.asset.inventory.model.query.MediaReconciliationEntry;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaReconciliationStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Stable, immutable result of a read-only media reconciliation. */
public record MediaReconciliationReport(List<MediaReconciliationEntry> entries) {
    private static final Comparator<MediaReconciliationEntry> ENTRY_ORDER =
            Comparator.comparing((MediaReconciliationEntry entry) -> entry.objectKey().value())
                    .thenComparing(MediaReconciliationEntry::kind)
                    .thenComparing(entry -> entry.status().name())
                    .thenComparing(MediaReconciliationEntry::ownerType)
                    .thenComparing(MediaReconciliationEntry::ownerId)
                    .thenComparing(MediaReconciliationEntry::label);

    public MediaReconciliationReport {
        Objects.requireNonNull(entries, "对账结果不能为空");
        entries =
                entries.stream()
                        .map(entry -> Objects.requireNonNull(entry, "对账明细不能为空"))
                        .sorted(ENTRY_ORDER)
                        .toList();
    }

    public long count(MediaReconciliationStatus status) {
        Objects.requireNonNull(status, "对账状态不能为空");
        return entries.stream().filter(entry -> entry.status() == status).count();
    }
}
