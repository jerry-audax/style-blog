package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.asset.inventory.result.MediaReconciliationReport;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

import org.springframework.stereotype.Service;

import java.util.Set;

/** Read-only application use case that combines provider inventory and business references. */
@Service
public final class MediaInventoryReconciliationApplicationService {
    private final MediaInventoryApplicationService inventory;
    private final MediaReferenceSnapshotApplicationService references;
    private final MediaInventoryReconciliationService reconciliation;

    public MediaInventoryReconciliationApplicationService(
            MediaInventoryApplicationService inventory,
            MediaReferenceSnapshotApplicationService references,
            MediaInventoryReconciliationService reconciliation) {
        this.inventory = inventory;
        this.references = references;
        this.reconciliation = reconciliation;
    }

    public MediaReconciliationReport reconcile(Set<MediaKind> requestedKinds) {
        var preview = inventory.preview(requestedKinds);
        var scopedReferences =
                references.collect().stream()
                        .filter(reference -> preview.byKind().containsKey(reference.kind()))
                        .toList();
        return reconciliation.reconcile(preview, scopedReferences);
    }
}
