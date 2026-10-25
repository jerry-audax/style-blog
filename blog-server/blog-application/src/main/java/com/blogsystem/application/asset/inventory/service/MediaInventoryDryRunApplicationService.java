package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.asset.inventory.result.MediaDryRunResult;
import com.blogsystem.domain.asset.inventory.model.query.MediaCatalogSnapshot;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaReconciliationStatus;
import com.blogsystem.domain.asset.inventory.repository.MediaSnapshotRepository;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Read-only inventory coordination; persistence, provider configuration and JSON stay outside. */
public final class MediaInventoryDryRunApplicationService {
    private final MediaSnapshotRepository snapshots;
    private final MediaInventoryApplicationService inventory;
    private final MediaInventoryReconciliationService reconciliation;
    private final String root;
    private final Clock clock;

    public MediaInventoryDryRunApplicationService(
            MediaSnapshotRepository snapshots,
            MediaInventoryApplicationService inventory,
            MediaInventoryReconciliationService reconciliation,
            String root,
            Clock clock) {
        this.snapshots = snapshots;
        this.inventory = inventory;
        this.reconciliation = reconciliation;
        this.root = root;
        this.clock = clock;
    }

    public MediaDryRunResult preview(Path music, Path surprise, boolean allowMissingSurprise) {
        List<MediaCatalogSnapshot> evidence = new ArrayList<>();
        evidence.add(snapshots.readMusic(music));
        var videoSnapshot = snapshots.readSurprise(surprise);
        if (!videoSnapshot.available() && !allowMissingSurprise)
            throw new MediaInventoryException("Missing surprise snapshot");
        evidence.add(videoSnapshot);
        List<String> warnings =
                videoSnapshot.available() ? List.of() : List.of("SURPRISE_SNAPSHOT_MISSING");
        List<MediaReference> references =
                evidence.stream().flatMap(snapshot -> snapshot.references().stream()).toList();
        references.forEach(reference -> reference.objectKey().requireWithin(root));
        EnumSet<MediaKind> kinds = EnumSet.of(MediaKind.IMAGE, MediaKind.AUDIO, MediaKind.VIDEO);
        if (references.stream().anyMatch(reference -> reference.kind() == MediaKind.LYRICS))
            kinds.add(MediaKind.LYRICS);
        var started = clock.instant();
        var preview = inventory.preview(kinds);
        var result = reconciliation.reconcile(preview, references);
        boolean complete = evidence.stream().allMatch(MediaCatalogSnapshot::available);
        int code =
                !complete
                                || result.count(MediaReconciliationStatus.MISSING) > 0
                                || result.count(MediaReconciliationStatus.CONFLICT) > 0
                        ? 2
                        : 0;
        return new MediaDryRunResult(
                started,
                clock.instant(),
                kinds,
                evidence,
                warnings,
                preview,
                result,
                complete,
                code);
    }
}
