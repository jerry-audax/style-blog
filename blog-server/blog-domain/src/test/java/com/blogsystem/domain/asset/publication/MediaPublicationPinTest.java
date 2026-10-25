package com.blogsystem.domain.asset.publication;

import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.publication.model.entity.MediaPublicationPin;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaPublicationPinTest {
    private static final MediaPublicationPinKey KEY = new MediaPublicationPinKey("local", "hexo", "Release-1");
    private static final String DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-18T01:02:03.123456789Z");

    @Test void emptyManifestStillHasAHeldVersionRoot() {
        var pin = MediaPublicationPin.held(KEY, DIGEST, Set.of(), NOW);
        assertThat(pin.key().releaseId()).isEqualTo("Release-1");
        assertThat(pin.assetIds()).isEmpty();
        assertThat(pin.status()).isEqualTo(MediaPublicationPin.Status.HELD);
        assertThat(pin.version()).isZero();
        assertThat(pin.createdAt()).isEqualTo(Instant.parse("2026-10-18T01:02:03.123456Z"));
        assertThat(pin.releasedAt()).isNull();
    }

    @Test void ownsAnImmutableDeduplicatedManifest() {
        UUID id = UUID.randomUUID();
        Set<UUID> ids = new HashSet<>();
        ids.add(id); ids.add(id);
        var pin = MediaPublicationPin.held(KEY, DIGEST, ids, NOW);
        ids.clear();
        assertThat(pin.assetIds()).containsExactly(id);
        assertThatThrownBy(() -> pin.assetIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsInvalidIdentifiersWithoutNormalizingIdentity() {
        for (String invalid : new String[]{"", "Local", "a_1", "a/1", "a".repeat(33), null}) {
            invalid(() -> new MediaPublicationPinKey(invalid, "hexo", "v1"));
            invalid(() -> new MediaPublicationPinKey("local", invalid, "v1"));
        }
        for (String invalid : new String[]{"", "v/1", "..", "v_1", "v".repeat(65), null}) {
            invalid(() -> new MediaPublicationPinKey("local", "hexo", invalid));
        }
    }

    @Test void rejectsInvalidDigestsAndManifestBounds() {
        for (String digest : new String[]{null, "", "a".repeat(63), "A".repeat(64), "g".repeat(64)}) {
            invalid(() -> MediaPublicationPin.held(KEY, digest, Set.of(), NOW));
        }
        invalid(() -> MediaPublicationPin.held(null, DIGEST, Set.of(), NOW));
        invalid(() -> MediaPublicationPin.held(KEY, DIGEST, null, NOW));
        Set<UUID> ids = new HashSet<>(); ids.add(null);
        invalid(() -> MediaPublicationPin.held(KEY, DIGEST, ids, NOW));
        ids.clear();
        for (int i = 0; i < 10000; i++) ids.add(new UUID(0, i));
        assertThat(MediaPublicationPin.held(KEY, DIGEST, ids, NOW).assetIds()).hasSize(10000);
        ids.add(new UUID(0, 10000));
        invalid(() -> MediaPublicationPin.held(KEY, DIGEST, ids, NOW));
    }

    @Test void releaseChecksVersionAndReturnsNewStateWithoutChangingManifest() {
        var pin = MediaPublicationPin.held(KEY, DIGEST, Set.of(UUID.randomUUID()), NOW);
        var released = pin.release(0, NOW.plusSeconds(1));
        assertThat(released.status()).isEqualTo(MediaPublicationPin.Status.RELEASED);
        assertThat(released.version()).isEqualTo(1);
        assertThat(released.releasedAt()).isEqualTo(Instant.parse("2026-10-18T01:02:04.123456Z"));
        assertThat(released.updatedAt()).isEqualTo(released.releasedAt());
        assertThat(released.key()).isEqualTo(pin.key());
        assertThat(released.manifestDigest()).isEqualTo(pin.manifestDigest());
        assertThat(released.assetIds()).isEqualTo(pin.assetIds());
        assertThat(released.createdAt()).isEqualTo(pin.createdAt());
        assertThat(pin.status()).isEqualTo(MediaPublicationPin.Status.HELD);
        assertThat(pin.version()).isZero();
        conflict(() -> pin.release(1, NOW));
        conflict(() -> released.release(1, NOW.plusSeconds(2)));
        conflict(() -> new MediaPublicationPin(KEY, DIGEST, Set.of(), MediaPublicationPin.Status.HELD,
                Long.MAX_VALUE, NOW, NOW, null).release(Long.MAX_VALUE, NOW));
    }

    @Test void rejectsInconsistentRestoredStateAndBackwardsTime() {
        invalid(() -> new MediaPublicationPin(KEY, DIGEST, Set.of(), MediaPublicationPin.Status.HELD, -1, NOW, NOW, null));
        invalid(() -> MediaPublicationPin.held(KEY, DIGEST, Set.of(), null));
        invalid(() -> new MediaPublicationPin(KEY, DIGEST, Set.of(), null, 0, NOW, NOW, null));
        invalid(() -> new MediaPublicationPin(KEY, DIGEST, Set.of(), MediaPublicationPin.Status.HELD, 0, NOW, NOW.minusSeconds(1), null));
        invalid(() -> new MediaPublicationPin(KEY, DIGEST, Set.of(), MediaPublicationPin.Status.HELD, 0, NOW, NOW, NOW));
        invalid(() -> new MediaPublicationPin(KEY, DIGEST, Set.of(), MediaPublicationPin.Status.RELEASED, 1, NOW, NOW, null));
        invalid(() -> new MediaPublicationPin(KEY, DIGEST, Set.of(), MediaPublicationPin.Status.RELEASED, 1, NOW, NOW.plusSeconds(1), NOW));
        invalid(() -> MediaPublicationPin.held(KEY, DIGEST, Set.of(), NOW).release(0, NOW.minusSeconds(1)));
    }

    private static void invalid(Runnable operation) { reason(operation, MediaPublicationPinException.Reason.INVALID_REQUEST); }
    private static void conflict(Runnable operation) { reason(operation, MediaPublicationPinException.Reason.CONFLICT); }
    private static void reason(Runnable operation, MediaPublicationPinException.Reason reason) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(MediaPublicationPinException.class,
                exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
