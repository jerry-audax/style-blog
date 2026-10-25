package com.blogsystem.application.asset.inventory.result;

import com.blogsystem.domain.asset.inventory.model.query.MediaCatalogSnapshot;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryPreview;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public record MediaDryRunResult(
        Instant scanStartedAt,
        Instant scanFinishedAt,
        Set<MediaKind> requestedKinds,
        List<MediaCatalogSnapshot> snapshots,
        List<String> warnings,
        MediaInventoryPreview inventory,
        MediaReconciliationReport reconciliation,
        boolean catalogSnapshotsComplete,
        int resultCode) {
    public MediaDryRunResult {
        requestedKinds = Collections.unmodifiableSet(EnumSet.copyOf(requestedKinds));
        snapshots = List.copyOf(snapshots);
        warnings = List.copyOf(warnings);
    }
}
