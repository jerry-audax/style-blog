package com.blogsystem.domain.asset.reference.policy;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaReferenceWritePolicyTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID SECOND = UUID.fromString("80000000-0000-0000-0000-000000000802");
    private final MediaReferenceWritePolicy policy = new MediaReferenceWritePolicy();

    @Test void actualHtmlReferencesAreImmutableAndCannotBeDroppedFromTheIntent() {
        var html = new ArrayList<>(List.of("    <img src='/file/blog/封面.png'>"));
        var intent = new MediaReferenceWriteIntent(List.of(), List.of(), html, Set.of());
        html.clear();
        assertEquals(List.of("    <img src='/file/blog/封面.png'>"), intent.htmlContents());
        assertThrows(UnsupportedOperationException.class, () -> intent.htmlContents().clear());
        assertThrows(IllegalArgumentException.class, () -> new MediaReferenceWriteIntent(List.of(), List.of(), null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new MediaReferenceWriteIntent(List.of(), List.of(), Arrays.asList((String) null), Set.of()));
    }

    // Removing defensive copies would let a caller change the references after validation.
    @Test void intentIsImmutableAndRejectsMissingContainers() {
        var links = new ArrayList<>(List.of("/file/blog/封面+100%.png"));
        var contents = new ArrayList<>(List.of("<p>正文</p>"));
        var ids = new HashSet<>(Set.of(FIRST));
        var intent = new MediaReferenceWriteIntent(links, contents, ids);
        links.clear(); contents.clear(); ids.add(SECOND);
        assertEquals(List.of("/file/blog/封面+100%.png"), intent.links());
        assertEquals(List.of("<p>正文</p>"), intent.contents());
        assertEquals(Set.of(FIRST), intent.assetIds());
        assertThrows(UnsupportedOperationException.class, () -> intent.links().clear());
        assertThrows(UnsupportedOperationException.class, () -> intent.contents().clear());
        assertThrows(UnsupportedOperationException.class, () -> intent.assetIds().clear());
        assertEquals(new MediaReferenceWriteIntent(List.of(), List.of(), Set.of()), MediaReferenceWriteIntent.empty());
        assertThrows(IllegalArgumentException.class, () -> new MediaReferenceWriteIntent(null, List.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new MediaReferenceWriteIntent(List.of(), null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new MediaReferenceWriteIntent(List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new MediaReferenceWriteIntent(Arrays.asList((String) null), List.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new MediaReferenceWriteIntent(List.of(), Arrays.asList((String) null), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new MediaReferenceWriteIntent(List.of(), List.of(), new HashSet<>(Arrays.asList((UUID) null))));
    }

    // Treating any non-ACTIVE state as bindable would create a link to a deleting file.
    @Test void requiresEveryDesiredAssetActive() {
        assertDoesNotThrow(() -> policy.requireActive(Set.of(FIRST), List.of(asset(FIRST, MediaAssetStatus.ACTIVE))));
        for (var status : List.of(MediaAssetStatus.DELETE_PENDING, MediaAssetStatus.DELETE_FAILED, MediaAssetStatus.DELETED)) {
            conflict(() -> policy.requireActive(Set.of(FIRST), List.of(asset(FIRST, status))));
        }
    }

    // A removed old reference must not prevent repairing a document or clearing an avatar.
    @Test void removalMayLockInactivePreviousAssets() {
        assertDoesNotThrow(() -> policy.requireActive(Set.of(SECOND), List.of(
                asset(FIRST, MediaAssetStatus.DELETE_PENDING), asset(SECOND, MediaAssetStatus.ACTIVE))));
        assertDoesNotThrow(() -> policy.requireActive(Set.of(), List.of(asset(FIRST, MediaAssetStatus.DELETED))));
        assertDoesNotThrow(() -> policy.requireActive(Set.of(), List.of()));
    }

    // Checking only the number of locked rows would accept the wrong asset identity.
    @Test void rejectsMissingDesiredOrWrongLockedIdentity() {
        conflict(() -> policy.requireActive(Set.of(FIRST), List.of()));
        conflict(() -> policy.requireActive(Set.of(FIRST), List.of(asset(SECOND, MediaAssetStatus.ACTIVE))));
        conflict(() -> policy.requireActive(Set.of(FIRST, SECOND), List.of(asset(FIRST, MediaAssetStatus.ACTIVE))));
    }

    // Duplicate or absent lock facts cannot be evidence that a reference is protected.
    @Test void rejectsDuplicateAndIncompleteLockFacts() {
        var active = asset(FIRST, MediaAssetStatus.ACTIVE);
        conflict(() -> policy.requireActive(Set.of(FIRST), List.of(active, active)));
        conflict(() -> policy.requireActive(Set.of(FIRST), Arrays.asList(active, null)));
        conflict(() -> policy.requireActive(Set.of(FIRST), null));
        conflict(() -> policy.requireActive(null, List.of(active)));
    }

    private static void conflict(Runnable operation) {
        var failure = assertThrows(MediaReferenceWriteException.class, operation::run);
        assertEquals(MediaReferenceWriteException.Reason.CONFLICT, failure.reason());
    }

    private static MediaAsset asset(UUID id, MediaAssetStatus status) {
        var now = Instant.parse("2026-10-18T00:00:00Z");
        return MediaAsset.restore(id, "cloudflare_imgbed", new MediaObjectKey("blog/images/" + id + ".png"),
                MediaKind.IMAGE, new MediaMetadata("测试.png", "image/png", 3L, null, null, null, null),
                status, 0, now, now);
    }
}
