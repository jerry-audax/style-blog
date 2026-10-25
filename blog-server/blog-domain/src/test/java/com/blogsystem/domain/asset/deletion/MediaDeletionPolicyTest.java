package com.blogsystem.domain.asset.deletion;

import com.blogsystem.domain.asset.deletion.model.valobj.*;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.*;
import com.blogsystem.domain.asset.deletion.policy.MediaDeletionPolicy;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MediaDeletionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00.123456789Z");
    private final MediaDeletionPolicy policy = new MediaDeletionPolicy();
    private MediaDeletionAssetSnapshot asset(MediaDeletionOwnership ownership) {
        return new MediaDeletionAssetSnapshot(MediaAsset.register(UUID.randomUUID(), "cloudflare_imgbed",
                new MediaObjectKey("blog/music/歌曲.mp3"), MediaKind.AUDIO,
                new MediaMetadata("歌曲.mp3", null, null, null, null, null, null), NOW), ownership);
    }
    private MediaDeletionOwnership exclusive(String environment) {
        return new MediaDeletionOwnership(MediaDeletionOwnership.Scope.EXCLUSIVE, environment, 7L, NOW, "已核对跨环境用途");
    }
    @Test void unknownOrSharedIsRetained() {
        var evidence = new MediaDeletionPolicy.Evidence("local", true, false, true);
        assertEquals(Reason.OWNERSHIP_UNVERIFIED, policy.evaluate(asset(MediaDeletionOwnership.unverified()), evidence).reason());
        var shared = new MediaDeletionOwnership(MediaDeletionOwnership.Scope.SHARED, null, 7L, NOW, "多环境共用");
        assertEquals(new MediaDeletionPolicy.Decision(ItemStatus.RETAINED, Reason.SHARED), policy.evaluate(asset(shared), evidence));
    }
    @Test void otherEnvironmentIsRetained() {
        assertEquals(Reason.ENVIRONMENT_MISMATCH, policy.evaluate(asset(exclusive("production")),
                new MediaDeletionPolicy.Evidence("local", true, false, true)).reason());
    }
    @Test void incompleteEvidenceNeverQueues() {
        assertEquals(new MediaDeletionPolicy.Decision(ItemStatus.RETAINED, Reason.REFERENCE_COVERAGE_INCOMPLETE),
                policy.evaluate(asset(exclusive("local")), new MediaDeletionPolicy.Evidence("local", false, false, false)));
    }
    @Test void completeExclusiveUnreferencedCanQueue() {
        assertEquals(ItemStatus.PENDING, policy.evaluate(asset(exclusive("local")),
                new MediaDeletionPolicy.Evidence("local", true, false, true)).status());
        assertEquals(Reason.IN_USE, policy.evaluate(asset(exclusive("local")),
                new MediaDeletionPolicy.Evidence("local", true, true, true)).reason());
        assertEquals(Reason.WRITE_GUARDS_INCOMPLETE, policy.evaluate(asset(exclusive("local")),
                new MediaDeletionPolicy.Evidence("local", true, false, false)).reason());
    }
    @Test void exclusiveRequiresAuditableOwnershipAndSafeNote() {
        assertThrows(IllegalArgumentException.class, () -> exclusive(""));
        assertThrows(IllegalArgumentException.class, () -> new MediaDeletionOwnership(MediaDeletionOwnership.Scope.EXCLUSIVE, "local", null, NOW, "核对"));
        assertThrows(IllegalArgumentException.class, () -> new MediaDeletionOwnership(MediaDeletionOwnership.Scope.EXCLUSIVE, "local", 7L, NOW, "https://private.example/file"));
        assertThrows(IllegalArgumentException.class, () -> new MediaDeletionOwnership(MediaDeletionOwnership.Scope.SHARED, "local", 7L, NOW, "共享"));
        assertEquals(123456000, exclusive("local").confirmedAt().getNano());
    }
    @Test void targetPreservesOpaqueIdAndRequiresExactSlot() {
        assertEquals("历史-ID", new MediaDeletionTarget(MediaDeletionTarget.Kind.MUSIC_TRACK,"历史-ID",null).id());
        assertThrows(IllegalArgumentException.class, () -> new MediaDeletionTarget(MediaDeletionTarget.Kind.MUSIC_MEDIA,"song",null));
        assertThrows(IllegalArgumentException.class, () -> new MediaDeletionTarget(MediaDeletionTarget.Kind.SURPRISE_VIDEO,"video",MediaDeletionTarget.Slot.AUDIO));
        assertThrows(IllegalArgumentException.class, () -> new MediaDeletionTarget(MediaDeletionTarget.Kind.MUSIC_TRACK,"x\n",null));
    }
}
