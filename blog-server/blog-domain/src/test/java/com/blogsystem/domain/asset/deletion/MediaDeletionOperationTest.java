package com.blogsystem.domain.asset.deletion;

import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.*;
import com.blogsystem.domain.asset.deletion.model.valobj.*;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaDeletionOperationTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private Item retained(UUID assetId) {
        return new Item(UUID.randomUUID(),assetId,MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),
                ItemStatus.RETAINED,Reason.OWNERSHIP_UNVERIFIED,null);
    }
    private MediaDeletionOperation operation(List<Item> items) {
        return new MediaDeletionOperation(UUID.randomUUID(),7,UUID.randomUUID().toString(),"a".repeat(64),
                new MediaDeletionTarget(MediaDeletionTarget.Kind.MUSIC_TRACK,"song",null),0,NOW,items);
    }
    @Test void retainedDoesNotMeanFileDeleted() {
        var receipt = operation(List.of(retained(UUID.randomUUID())));
        assertTrue(receipt.processingComplete());
        assertFalse(receipt.allFilesDeleted());
    }
    @Test void emptyReceiptIsReplayable() {
        var receipt = operation(List.of());
        assertTrue(receipt.processingComplete());
        assertFalse(receipt.allFilesDeleted());
        assertNotNull(receipt.idempotencyKey());
    }
    @Test void duplicateAssetItemsRejected() {
        UUID asset = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> operation(List.of(retained(asset),retained(asset))));
    }
    @Test void linkedItemMustUseSameCanonicalAsset() {
        var canonical = retained(UUID.randomUUID());
        var linked = new Item(UUID.randomUUID(),canonical.assetId(),MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.LINKED,Reason.NONE,canonical);
        assertEquals(ItemStatus.RETAINED,linked.resolvedStatus());
        assertTrue(operation(List.of(linked)).processingComplete());
        assertFalse(operation(List.of(linked)).allFilesDeleted());
        assertThrows(IllegalArgumentException.class, () -> new Item(canonical.id(),canonical.assetId(),MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.LINKED,Reason.NONE,canonical));
        assertThrows(IllegalArgumentException.class, () -> new Item(UUID.randomUUID(),UUID.randomUUID(),MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.LINKED,Reason.NONE,canonical));
        assertThrows(IllegalArgumentException.class, () -> new Item(UUID.randomUUID(),linked.assetId(),MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.LINKED,Reason.NONE,linked));
    }
    @Test void pendingAndSuccessHaveDifferentCompletionMeaning() {
        UUID asset = UUID.randomUUID();
        var pending = new Item(UUID.randomUUID(),asset,MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.PENDING,Reason.NONE,null);
        assertFalse(operation(List.of(pending)).processingComplete());
        var success = new Item(UUID.randomUUID(),asset,MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.SUCCEEDED,Reason.NONE,null);
        assertTrue(operation(List.of(success)).allFilesDeleted());
        assertThrows(IllegalArgumentException.class, () -> new Item(UUID.randomUUID(),asset,MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.RETAINED,Reason.NONE,null));
    }
    @Test void receiptsDefensivelyCopyItemsAndRejectInvalidOwnerVersion() {
        List<Item> items = new ArrayList<>();
        var receipt = operation(items); items.add(retained(UUID.randomUUID()));
        assertTrue(receipt.items().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> receipt.items().add(items.get(0)));
        assertThrows(IllegalArgumentException.class, () -> new MediaDeletionOperation(UUID.randomUUID(),0,"key","a".repeat(64),receipt.target(),0,NOW,List.of()));
    }
}
