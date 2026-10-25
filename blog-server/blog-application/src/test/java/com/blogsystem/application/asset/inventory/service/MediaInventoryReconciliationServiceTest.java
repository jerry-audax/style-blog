package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.asset.inventory.result.MediaReconciliationReport;
import com.blogsystem.application.asset.inventory.service.MediaInventoryReconciliationService;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryPreview;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaReconciliationStatus;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaInventoryReconciliationServiceTest {
    @Test
    void classifiesMatchedMissingAndNewWithoutWritingEitherSide() {
        MediaInventoryObject matchedAudio = item(MediaKind.AUDIO, "blog/music/matched.mp3");
        MediaInventoryObject newImage = item(MediaKind.IMAGE, "blog/images/new.png");
        MediaInventoryPreview inventory = new MediaInventoryPreview(Map.of(
                MediaKind.AUDIO, List.of(matchedAudio),
                MediaKind.IMAGE, List.of(newImage)));
        List<MediaReference> references = List.of(
                reference(MediaKind.AUDIO, "track-1", "blog/music/matched.mp3"),
                reference(MediaKind.AUDIO, "track-2", "blog/music/missing.mp3"));

        MediaReconciliationReport report =
                new MediaInventoryReconciliationService().reconcile(inventory, references);

        assertEquals(1, report.count(MediaReconciliationStatus.MATCHED));
        assertEquals(1, report.count(MediaReconciliationStatus.MISSING));
        assertEquals(1, report.count(MediaReconciliationStatus.NEW));
        assertEquals(0, report.count(MediaReconciliationStatus.CONFLICT));
        assertEquals("blog/images/new.png", report.entries().get(0).objectKey().value());
    }

    @Test
    void marksSameObjectReferencedAsDifferentKindsAsConflict() {
        MediaInventoryPreview inventory = new MediaInventoryPreview(Map.of(
                MediaKind.AUDIO, List.of(item(MediaKind.AUDIO, "blog/shared/object.bin"))));
        List<MediaReference> references = List.of(
                reference(MediaKind.AUDIO, "track-1", "blog/shared/object.bin"),
                reference(MediaKind.IMAGE, "article-1", "blog/shared/object.bin"));

        MediaReconciliationReport report =
                new MediaInventoryReconciliationService().reconcile(inventory, references);

        assertEquals(2, report.count(MediaReconciliationStatus.CONFLICT));
        assertEquals(0, report.count(MediaReconciliationStatus.MATCHED));
        assertEquals(0, report.count(MediaReconciliationStatus.NEW));
    }

    @Test
    void marksProviderObjectReportedUnderMultipleKindsAsConflict() {
        MediaInventoryPreview inventory = new MediaInventoryPreview(Map.of(
                MediaKind.AUDIO, List.of(item(MediaKind.AUDIO, "blog/shared/object.bin")),
                MediaKind.IMAGE, List.of(item(MediaKind.IMAGE, "blog/shared/object.bin"))));
        List<MediaReference> references = List.of(
                reference(MediaKind.AUDIO, "track-1", "blog/shared/object.bin"));

        MediaReconciliationReport report =
                new MediaInventoryReconciliationService().reconcile(inventory, references);

        assertEquals(1, report.count(MediaReconciliationStatus.CONFLICT));
        assertEquals(0, report.count(MediaReconciliationStatus.MATCHED));
        assertEquals(0, report.count(MediaReconciliationStatus.NEW));
    }

    @Test
    void reportsUnreferencedProviderTypeCollisionAsConflicts() {
        MediaInventoryPreview inventory = new MediaInventoryPreview(Map.of(
                MediaKind.AUDIO, List.of(item(MediaKind.AUDIO, "blog/shared/object.bin")),
                MediaKind.IMAGE, List.of(item(MediaKind.IMAGE, "blog/shared/object.bin"))));

        MediaReconciliationReport report =
                new MediaInventoryReconciliationService().reconcile(inventory, List.of());

        assertEquals(2, report.count(MediaReconciliationStatus.CONFLICT));
        assertEquals(0, report.count(MediaReconciliationStatus.NEW));
    }

    @Test
    void rejectsNullReconciliationInputs() {
        MediaInventoryReconciliationService service = new MediaInventoryReconciliationService();
        assertThrows(NullPointerException.class, () -> service.reconcile(null, List.of()));
        assertThrows(NullPointerException.class, () -> service.reconcile(
                new MediaInventoryPreview(Map.of()), null));
    }

    @Test
    void rejectsDuplicateInventoryObjectsInsteadOfSilentlyDeduplicating() {
        var object = item(MediaKind.AUDIO, "blog/music/a.mp3");
        var inventory = new MediaInventoryPreview(Map.of(MediaKind.AUDIO, List.of(object, object)));
        assertThrows(MediaInventoryException.class,
                () -> new MediaInventoryReconciliationService().reconcile(inventory, List.of()));
    }

    private static MediaInventoryObject item(MediaKind kind, String path) {
        return new MediaInventoryObject(kind, new MediaObjectKey(path),
                new MediaMetadata(path.substring(path.lastIndexOf('/') + 1), null, null, null, null, null, null));
    }

    private static MediaReference reference(MediaKind kind, String ownerId, String path) {
        return new MediaReference(kind, new MediaObjectKey(path), "fixture", ownerId, ownerId);
    }
}
