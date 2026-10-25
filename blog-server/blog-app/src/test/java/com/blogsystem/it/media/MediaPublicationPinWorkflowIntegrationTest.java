package com.blogsystem.it.media;

import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationPinRepository;
import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.publication.model.entity.MediaPublicationPin;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaPublicationPinWorkflowIntegrationTest {
    private static final MediaPublicationPinKey KEY = MediaPublicationPinRepositoryIntegrationTest.KEY;
    private static final String DIGEST = MediaPublicationPinRepositoryIntegrationTest.DIGEST;
    private static final UUID AUDIO = UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO);
    private static final UUID COVER = UUID.fromString(MediaRuntimeRepositoryIntegrationTest.IMAGE);

    @Test void emptyAndRepeatedManifestHaveExactlyOneRootAndCrossEnvironmentPinsRemainVisible() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            var pin = fixture.service.prepare(KEY, DIGEST, Set.of());
            assertEquals(pin, fixture.service.prepare(KEY, DIGEST, Set.of()));
            assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
            var second = new MediaPublicationPinKey("production", "hexo", "Another");
            var third = new MediaPublicationPinKey("local", "another", "Another");
            fixture.service.prepare(second, DIGEST, Set.of(AUDIO)); fixture.service.prepare(third, DIGEST, Set.of(AUDIO));
            assertEquals(List.of(third, second), fixture.service.holding(AUDIO).stream().map(MediaPublicationPin::key).toList());
            assertEquals(pin, fixture.service.get(KEY));
            reason(MediaPublicationPinException.Reason.NOT_FOUND, () -> fixture.service.get(new MediaPublicationPinKey("local", "hexo", "missing")));
        }
    }

    @Test void differentDigestOrAssetSetCannotChangeExistingManifest() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            var original = fixture.service.prepare(KEY, DIGEST, Set.of(AUDIO));
            reason(MediaPublicationPinException.Reason.CONFLICT, () -> fixture.service.prepare(KEY, "c".repeat(64), Set.of(AUDIO)));
            reason(MediaPublicationPinException.Reason.CONFLICT, () -> fixture.service.prepare(KEY, DIGEST, Set.of(COVER)));
            assertEquals(original, fixture.service.get(KEY));
            assertEquals(0L, fixture.assets.findById(AUDIO).orElseThrow().asset().version());
        }
    }

    @Test void missingOrInactiveAssetsRejectWholeManifestWithoutPartialInsert() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            reason(MediaPublicationPinException.Reason.CONFLICT, () -> fixture.service.prepare(KEY, DIGEST, Set.of(AUDIO, UUID.randomUUID())));
            for (String status : List.of("DELETE_PENDING", "DELETE_FAILED", "DELETED")) {
                fixture.schema.execute("UPDATE media_asset SET status='" + status + "' WHERE id='" + COVER + "'");
                reason(MediaPublicationPinException.Reason.CONFLICT, () -> fixture.service.prepare(KEY, DIGEST, Set.of(AUDIO, COVER)));
            }
            assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
            assertEquals(0L, fixture.assets.findById(AUDIO).orElseThrow().asset().version());
        }
    }

    @Test void releaseIsReplayableButNeverReactivatesAnOldReleaseOrChangesAssetMetadata() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            var asset = fixture.assets.findById(AUDIO).orElseThrow();
            fixture.service.prepare(KEY, DIGEST, Set.of(AUDIO));
            reason(MediaPublicationPinException.Reason.CONFLICT, () -> fixture.service.release(KEY, 1, DIGEST));
            reason(MediaPublicationPinException.Reason.CONFLICT, () -> fixture.service.release(KEY, 0, "c".repeat(64)));
            var released = fixture.service.release(KEY, 0, DIGEST);
            assertEquals(MediaPublicationPin.Status.RELEASED, released.status()); assertEquals(1, released.version());
            assertEquals(released, fixture.service.release(KEY, 0, DIGEST));
            reason(MediaPublicationPinException.Reason.CONFLICT, () -> fixture.service.release(KEY, 1, DIGEST));
            reason(MediaPublicationPinException.Reason.CONFLICT, () -> fixture.service.release(KEY, Long.MAX_VALUE, DIGEST));
            reason(MediaPublicationPinException.Reason.CONFLICT, () -> fixture.service.prepare(KEY, DIGEST, Set.of(AUDIO)));
            assertTrue(fixture.service.holding(AUDIO).isEmpty()); assertEquals(asset, fixture.assets.findById(AUDIO).orElseThrow());
        }
    }

    @Test void failureAfterActualInsertRollsBackAndLeavesTheAssetReusable() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            var failing = fixture.service(new Forwarding(fixture.pins) {
                @Override public MediaPublicationPin insertHeld(MediaPublicationPin pin) {
                    super.insertHeld(pin);
                    throw new MediaPublicationPinException(MediaPublicationPinException.Reason.UNAVAILABLE);
                }
            });
            reason(MediaPublicationPinException.Reason.UNAVAILABLE, () -> failing.prepare(KEY, DIGEST, Set.of(AUDIO)));
            assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
            assertEquals(0L, fixture.assets.findById(AUDIO).orElseThrow().asset().version());
            assertEquals(Set.of(AUDIO), fixture.service.prepare(KEY, DIGEST, Set.of(AUDIO)).assetIds());
        }
    }

    @Test void twoConcurrentIdenticalPreparesReturnOneDurableManifest() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
            try {
                var first = pool.submit(() -> { await(start); return fixture.service.prepare(KEY, DIGEST, Set.of(AUDIO)); });
                var second = pool.submit(() -> { await(start); return fixture.service.prepare(KEY, DIGEST, Set.of(AUDIO)); });
                start.countDown(); assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void concurrentDifferentAssetsHitUniqueConstraintThenReplayInAFreshTransaction() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            var inserted = new CountDownLatch(1); var secondInsert = new CountDownLatch(1); var finish = new CountDownLatch(1);
            var racing = fixture.service(new Forwarding(fixture.pins) {
                @Override public MediaPublicationPin insertHeld(MediaPublicationPin pin) {
                    if (pin.assetIds().contains(AUDIO)) {
                        var result = super.insertHeld(pin); inserted.countDown(); await(finish); return result;
                    }
                    secondInsert.countDown(); return super.insertHeld(pin);
                }
            });
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> racing.prepare(KEY, DIGEST, Set.of(AUDIO)));
                assertTrue(inserted.await(10, TimeUnit.SECONDS));
                var second = pool.submit(() -> assertThrows(MediaPublicationPinException.class, () -> racing.prepare(KEY, DIGEST, Set.of(COVER))));
                assertTrue(secondInsert.await(10, TimeUnit.SECONDS)); assertFalse(second.isDone()); finish.countDown();
                assertEquals(Set.of(AUDIO), first.get(10, TimeUnit.SECONDS).assetIds());
                assertEquals(MediaPublicationPinException.Reason.CONFLICT, second.get(10, TimeUnit.SECONDS).reason());
                assertEquals(Set.of(AUDIO), fixture.service.get(KEY).assetIds());
                assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
                assertEquals(0L, fixture.assets.findById(COVER).orElseThrow().asset().version());
            } finally { finish.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void concurrentEmptyReleaseReplaysTheCasWinner() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            fixture.service.prepare(KEY, DIGEST, Set.of());
            var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> { await(start); return fixture.service.release(KEY, 0, DIGEST); });
                var second = pool.submit(() -> { await(start); return fixture.service.release(KEY, 0, DIGEST); });
                start.countDown(); assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertEquals(1, fixture.service.get(KEY).version());
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void moreThanOneLockBatchRetainsGlobalUuidOrderAndExactManifest() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            Set<UUID> ids = new HashSet<>();
            for (int i = 0; i < 1001; i++) ids.add(new UUID(i % 2 == 0 ? 0 : Long.MIN_VALUE, i + 10));
            for (UUID id : ids) fixture.jdbc.update("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES (?,'cloudflare_imgbed',?,'IMAGE','test.png','ACTIVE',now(),now())", id, "blog/test/" + id);
            List<UUID> locked = new ArrayList<>();
            var observedAssets = new com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository() {
                @Override public Optional<com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot> findById(UUID id) { return fixture.assets.findById(id); }
                @Override public List<com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot> lockByIds(Set<UUID> batch) {
                    var result = fixture.assets.lockByIds(batch); result.forEach(value -> locked.add(value.asset().id())); return result;
                }
                @Override public com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot confirmOwnership(UUID id,
                        com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership ownership, long version, Instant now) {
                    return fixture.assets.confirmOwnership(id, ownership, version, now);
                }
            };
            var service = new com.blogsystem.application.asset.publication.service.MediaPublicationPinApplicationService(fixture.pins, observedAssets, fixture.tx, fixture.clock);
            assertEquals(ids, service.prepare(KEY, DIGEST, ids).assetIds());
            assertEquals(ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList(), locked);
            assertEquals(1001, locked.size());
        }
    }

    @Test void malformedCommandsAndAmbientTransactionsCannotLeavePartialWork() throws Exception {
        try (var fixture = new MediaPublicationPinWorkflowFixture()) {
            reason(MediaPublicationPinException.Reason.INVALID_REQUEST, () -> fixture.service.prepare(KEY, "bad", Set.of()));
            reason(MediaPublicationPinException.Reason.INVALID_REQUEST, () -> fixture.service.release(KEY, -1, DIGEST));
            reason(MediaPublicationPinException.Reason.INVALID_REQUEST, () -> fixture.tx.execute(status -> fixture.service.prepare(KEY, DIGEST, Set.of())));
            assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void reason(MediaPublicationPinException.Reason reason, Runnable operation) {
        assertEquals(reason, assertThrows(MediaPublicationPinException.class, operation::run).reason());
    }
    private static class Forwarding implements MediaPublicationPinRepository {
        final MediaPublicationPinRepository delegate;
        Forwarding(MediaPublicationPinRepository delegate) { this.delegate = delegate; }
        @Override public MediaPublicationPin insertHeld(MediaPublicationPin pin) { return delegate.insertHeld(pin); }
        @Override public Optional<MediaPublicationPin> findByKey(MediaPublicationPinKey key) { return delegate.findByKey(key); }
        @Override public List<MediaPublicationPin> findHolding(UUID asset) { return delegate.findHolding(asset); }
        @Override public MediaPublicationPin release(MediaPublicationPinKey key, long version, Instant now) { return delegate.release(key, version, now); }
    }
}
