package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.asset.inventory.result.MediaReconciliationReport;
import com.blogsystem.application.asset.inventory.service.MediaInventoryApplicationService;
import com.blogsystem.application.asset.inventory.service.MediaInventoryReconciliationApplicationService;
import com.blogsystem.application.asset.inventory.service.MediaInventoryReconciliationService;
import com.blogsystem.application.asset.inventory.service.MediaReferenceSnapshotApplicationService;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryPreview;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaReconciliationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaInventoryReconciliationApplicationServiceTest {
    @Test
    void runsProviderInventoryAndBusinessReferenceSnapshotAsOneReadOnlyUseCase() {
        MediaInventoryApplicationService inventory = new MediaInventoryApplicationService(kind -> List.of(
                new MediaInventoryObject(kind, new MediaObjectKey("blog/music/a.mp3"),
                        new MediaMetadata("a.mp3", null, 1L, null, null, null, null))));
        MediaReferenceSnapshotApplicationService references =
                new MediaReferenceSnapshotApplicationService(List.of(() -> List.of(
                        new MediaReference(MediaKind.AUDIO, new MediaObjectKey("blog/music/a.mp3"),
                                "music_track", "track-1", "A"))));
        MediaInventoryReconciliationApplicationService service =
                new MediaInventoryReconciliationApplicationService(
                        inventory, references, new MediaInventoryReconciliationService());

        MediaReconciliationReport report = service.reconcile(Set.of(MediaKind.AUDIO));

        assertEquals(1, report.count(MediaReconciliationStatus.MATCHED));
        assertEquals(0, report.count(MediaReconciliationStatus.NEW));
    }

    @Test
    void doesNotReportUnscannedReferenceKindsAsMissing() {
        var inventory = new MediaInventoryApplicationService(kind -> List.of());
        var references = new MediaReferenceSnapshotApplicationService(List.of(() -> List.of(
                new MediaReference(MediaKind.IMAGE, new MediaObjectKey("blog/covers/a.jpg"),
                        "music_track", "track-1", "Cover"))));
        var service = new MediaInventoryReconciliationApplicationService(
                inventory, references, new MediaInventoryReconciliationService());

        assertEquals(0, service.reconcile(Set.of(MediaKind.AUDIO)).entries().size());
    }
}
