package com.blogsystem.it.media;

import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;
import com.blogsystem.app.config.asset.MediaReferenceWriteConfiguration;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaReferenceWriteRepositoryIntegrationTest {
    private static final UUID OLD = UUID.fromString("00000000-0000-0000-0000-000000000811");
    private static final UUID NEXT = UUID.fromString("80000000-0000-0000-0000-000000000812");
    private static final MediaReferenceWriteIntent EMPTY = MediaReferenceWriteIntent.empty();

    // A lookup bypass or missing old-reference lock would lose one of these actual locked rows.
    @Test void locksRegisteredLinksAndIdsWithoutProviderCalls() throws Exception {
        try (var f = new MediaReferenceWriteFixture()) {
            f.insert(OLD, "blog/images/旧封面.png");
            f.insert(NEXT, "blog/images/中文+100%.png");
            var locked = new ArrayList<UUID>();
            var guard = f.guard(observe(f.locks, rows -> rows.forEach(row -> locked.add(row.asset().id()))));
            var desired = new MediaReferenceWriteIntent(List.of("https://old.example/file/blog/images/中文+100%25.png?width=200", "https://external.example/cover.png"),
                    List.of("<img srcset='/file/blog/images/中文+100%25.png 1x, /file/blog/images/中文+100%25.png 2x'>"), Set.of(NEXT));
            f.tx.executeWithoutResult(status -> guard.lockForWrite(links("/file/blog/images/旧封面.png"), desired));
            assertEquals(List.of(OLD, NEXT), locked);
            assertEquals(2, f.jdbc.queryForObject("SELECT count(*) FROM media_asset", Integer.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_delete_operation", Integer.class));
        }
    }

    // Unknown paths and post-lock non-ACTIVE facts must not become usable saved links.
    @Test void rejectsUnknownOrDeletingDesiredButAllowsRemoval() throws Exception {
        try (var f = new MediaReferenceWriteFixture()) {
            f.insert(OLD, "blog/images/old.png");
            conflict(() -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(EMPTY, links("/file/blog/images/missing.png"))));
            f.jdbc.update("UPDATE media_asset SET status='DELETE_PENDING' WHERE id=?", OLD);
            conflict(() -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(EMPTY, ids(OLD))));
            assertDoesNotThrow(() -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(ids(OLD), EMPTY)));
            assertDoesNotThrow(() -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(links("/file/blog/images/missing.png"), EMPTY)));
            conflict(() -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(EMPTY, links("/file/blog/%252e%252e/a.png"))));
            conflict(() -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(EMPTY, new MediaReferenceWriteIntent(List.of(), List.of("x".repeat(1048577)), Set.of()))));
            assertDoesNotThrow(() -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(EMPTY, links("https://external.example/a.png"))));
        }
    }

    // LIKE, case folding, empty IN clauses or per-object selects would violate lookup identity/cost.
    @Test void locationLookupIsLiteralBatchedAndEmptyLookupDoesNotQuery() throws Exception {
        try (var f = new MediaReferenceWriteFixture()) {
            f.insert(OLD, "blog/images/中文+100%.png");
            f.insert(NEXT, "blog/images/中文+100X.png");
            f.trace.statements.clear();
            assertTrue(f.assets.findByLocations("cloudflare_imgbed", Set.of()).isEmpty());
            assertTrue(f.trace.statements.isEmpty());
            assertEquals(List.of(OLD), f.assets.findByLocations("cloudflare_imgbed", Set.of(new MediaObjectKey("blog/images/中文+100%.png"))).stream().map(a -> a.id()).toList());
            assertTrue(f.assets.findByLocations("other", Set.of(new MediaObjectKey("blog/images/中文+100%.png"))).isEmpty());
            assertTrue(f.assets.findByLocations("cloudflare_imgbed", Set.of(new MediaObjectKey("blog/images/中文+100%.PNG"))).isEmpty());
            var tooMany = new HashSet<MediaObjectKey>();
            for (int n = 0; n < 1001; n++) tooMany.add(new MediaObjectKey("blog/" + n));
            assertThrows(IllegalArgumentException.class, () -> f.assets.findByLocations("cloudflare_imgbed", tooMany));
        }
    }

    // Java's signed UUID order or per-batch-only sorting would differ from real PostgreSQL ordering.
    @Test void orderedBatchesMatchPostgresAcrossUuidSignBoundary() throws Exception {
        try (var f = new MediaReferenceWriteFixture()) {
            var all = largeIds(); f.insertMany(all);
            List<UUID> expected = f.jdbc.queryForList("SELECT id FROM media_asset ORDER BY id", UUID.class);
            var locked = new ArrayList<UUID>();
            var guard = f.guard(observe(f.locks, rows -> rows.forEach(row -> locked.add(row.asset().id()))));
            var urls = all.stream().map(id -> "/file/blog/images/" + id + ".png").toList();
            f.trace.statements.clear();
            f.tx.executeWithoutResult(s -> guard.lockForWrite(EMPTY, new MediaReferenceWriteIntent(urls, List.of(), Set.of())));
            assertEquals(expected, locked); assertEquals(1001, locked.size());
            assertEquals(2, f.trace.containing("object_key in"));
            assertEquals(2, f.trace.containing("for update"));
        }
    }

    // Calling the guard outside a transaction must not release every lock before the actual save.
    @Test void missingTransactionDatabaseOrManagedScopeNeverFallsBack() throws Exception {
        try (var f = new MediaReferenceWriteFixture()) {
            f.insert(OLD, "blog/images/a.png");
            var absentTx = assertThrows(MediaReferenceWriteException.class, () -> f.guard.lockForWrite(EMPTY, ids(OLD)));
            assertEquals(MediaReferenceWriteException.Reason.UNAVAILABLE, absentTx.reason());
            f.jdbc.update("UPDATE media_asset SET provider='other' WHERE id=?", OLD);
            conflict(() -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(EMPTY, ids(OLD))));
            f.jdbc.update("UPDATE media_asset SET provider='cloudflare_imgbed',object_key='other/a.png' WHERE id=?", OLD);
            conflict(() -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(EMPTY, ids(OLD))));
            f.schema.execute("ALTER TABLE media_asset RENAME TO temporarily_unavailable_assets");
            var unavailable = assertThrows(MediaReferenceWriteException.class, () -> f.tx.executeWithoutResult(s -> f.guard.lockForWrite(EMPTY, ids(OLD))));
            assertEquals(MediaReferenceWriteException.Reason.UNAVAILABLE, unavailable.reason());
            assertNull(unavailable.getCause()); assertFalse(unavailable.getMessage().contains("SELECT"));
        }
    }

    @Test void changedLocationAfterLookupCannotBeAcceptedAsTheOriginalLink() throws Exception {
        try (var f = new MediaReferenceWriteFixture()) {
            f.insert(OLD, "blog/images/original.png");
            var changing = new MediaDeletionAssetRepository() {
                @Override public java.util.Optional<MediaDeletionAssetSnapshot> findById(UUID id) { return f.locks.findById(id); }
                @Override public List<MediaDeletionAssetSnapshot> lockByIds(Set<UUID> ids) {
                    f.jdbc.update("UPDATE media_asset SET object_key='blog/images/changed.png' WHERE id=?", OLD);
                    return f.locks.lockByIds(ids);
                }
                @Override public MediaDeletionAssetSnapshot confirmOwnership(UUID id, MediaDeletionOwnership ownership, long version, Instant now) { return f.locks.confirmOwnership(id, ownership, version, now); }
            };
            conflict(() -> f.tx.executeWithoutResult(s -> f.guard(changing).lockForWrite(EMPTY, links("/file/blog/images/original.png"))));
            assertEquals("blog/images/original.png", f.jdbc.queryForObject("SELECT object_key FROM media_asset WHERE id=?", String.class, OLD));
        }
    }

    @Test void storageModeWiringIsExplicitAndUploadSwitchDoesNotDisableProtection() throws Exception {
        var runner = new ApplicationContextRunner().withUserConfiguration(MediaReferenceWriteConfiguration.class);
        runner.run(context -> assertDoesNotThrow(() -> context.getBean(MediaReferenceWriteRepository.class).lockForWrite(EMPTY, EMPTY)));
        runner.withPropertyValues("media.catalog.storage=legacy-json").run(context ->
                assertDoesNotThrow(() -> context.getBean(MediaReferenceWriteRepository.class).lockForWrite(EMPTY, EMPTY)));
        runner.withPropertyValues("media.catalog.storage=unknown").run(context -> assertTrue(context.getBeansOfType(MediaReferenceWriteRepository.class).isEmpty()));
        try (var f = new MediaReferenceWriteFixture()) {
            runner.withPropertyValues("media.catalog.storage=postgres", "media.catalog.uploads.enabled=false")
                    .withBean(MediaAssetRepository.class, () -> f.assets)
                    .withBean(MediaDeletionAssetRepository.class, () -> f.locks)
                    .withBean(MediaReferenceLinkGateway.class, () -> f.links)
                    .run(context -> assertEquals(MediaReferenceWriteException.Reason.UNAVAILABLE,
                            assertThrows(MediaReferenceWriteException.class, () -> context.getBean(MediaReferenceWriteRepository.class).lockForWrite(EMPTY, EMPTY)).reason()));
        }
        runner.withPropertyValues("media.catalog.storage=postgres").run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test @Timeout(30) void deletionFirstMakesWriterConflict() throws Exception {
        try (var f = new MediaReferenceWriteFixture(); var deleting = f.schema.openConnection()) {
            f.insert(OLD, "blog/images/a.png"); deleting.setAutoCommit(false);
            transition(deleting, OLD);
            var pool = Executors.newSingleThreadExecutor(); var started = new CountDownLatch(1); var pid = new AtomicInteger();
            try {
                var writer = pool.submit(() -> assertThrows(MediaReferenceWriteException.class, () -> f.tx.executeWithoutResult(s -> {
                    pid.set(f.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); started.countDown(); f.guard.lockForWrite(EMPTY, ids(OLD));
                })));
                assertTrue(started.await(10, TimeUnit.SECONDS)); awaitBlocked(f, pid.get());
                assertFalse(writer.isDone()); deleting.commit();
                assertEquals(MediaReferenceWriteException.Reason.CONFLICT, writer.get(10, TimeUnit.SECONDS).reason());
                assertEquals("DELETE_PENDING", f.jdbc.queryForObject("SELECT status FROM media_asset WHERE id=?", String.class, OLD));
            } finally { deleting.rollback(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test @Timeout(30) void writerFirstBlocksTransitionUntilCommit() throws Exception {
        try (var f = new MediaReferenceWriteFixture()) {
            f.insert(OLD, "blog/images/a.png");
            var pool = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
            var deletingStarted = new CountDownLatch(1); var pid = new AtomicInteger();
            try {
                var writer = pool.submit(() -> f.tx.executeWithoutResult(s -> {
                    f.guard.lockForWrite(EMPTY, ids(OLD)); held.countDown(); await(release);
                }));
                assertTrue(held.await(10, TimeUnit.SECONDS));
                var deleting = pool.submit(() -> f.tx.executeWithoutResult(s -> {
                    pid.set(f.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); deletingStarted.countDown();
                    f.jdbc.update("UPDATE media_asset SET status='DELETE_PENDING',version=version+1 WHERE id=?", OLD);
                }));
                assertTrue(deletingStarted.await(10, TimeUnit.SECONDS)); awaitBlocked(f, pid.get());
                assertFalse(deleting.isDone()); release.countDown(); writer.get(10, TimeUnit.SECONDS); deleting.get(10, TimeUnit.SECONDS);
                assertEquals("DELETE_PENDING", f.jdbc.queryForObject("SELECT status FROM media_asset WHERE id=?", String.class, OLD));
            } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test @Timeout(30) void oppositeInputOrderDoesNotDeadlock() throws Exception {
        try (var f = new MediaReferenceWriteFixture()) {
            var all = largeIds(); f.insertMany(all);
            var forward = all.stream().map(id -> "/file/blog/images/" + id + ".png").toList();
            var reverse = new ArrayList<>(forward); Collections.reverse(reverse);
            var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1); var ready = new CountDownLatch(2);
            var pids = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
            try {
                var first = pool.submit(() -> f.tx.executeWithoutResult(s -> { pids.add(f.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); ready.countDown(); await(start); f.guard.lockForWrite(EMPTY, new MediaReferenceWriteIntent(forward, List.of(), Set.of())); }));
                var second = pool.submit(() -> f.tx.executeWithoutResult(s -> { pids.add(f.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); ready.countDown(); await(start); f.guard.lockForWrite(EMPTY, new MediaReferenceWriteIntent(reverse, List.of(), Set.of())); }));
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown(); first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
                assertEquals(2, pids.size()); assertEquals(1001, f.jdbc.queryForObject("SELECT count(*) FROM media_asset WHERE status='ACTIVE'", Integer.class));
            } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    private static List<UUID> largeIds() {
        var ids = new ArrayList<UUID>();
        for (int n = 0; n < 1001; n++) ids.add(new UUID(n % 2 == 0 ? 0x7fff000000000000L : Long.MIN_VALUE, n + 4096));
        return ids;
    }
    private static MediaReferenceWriteIntent links(String url) { return new MediaReferenceWriteIntent(List.of(url), List.of(), Set.of()); }
    private static MediaReferenceWriteIntent ids(UUID id) { return new MediaReferenceWriteIntent(List.of(), List.of(), Set.of(id)); }
    private static void conflict(Runnable action) { assertEquals(MediaReferenceWriteException.Reason.CONFLICT, assertThrows(MediaReferenceWriteException.class, action::run).reason()); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void awaitBlocked(MediaReferenceWriteFixture f, int pid) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            if (Boolean.TRUE.equals(f.jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0", Boolean.class, pid))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected a real PostgreSQL row-lock wait");
    }
    private static void transition(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("UPDATE media_asset SET status='DELETE_PENDING',version=version+1 WHERE id=?")) { statement.setObject(1, id); assertEquals(1, statement.executeUpdate()); }
    }
    private static MediaDeletionAssetRepository observe(MediaDeletionAssetRepository actual, Consumer<List<MediaDeletionAssetSnapshot>> observed) {
        return new MediaDeletionAssetRepository() {
            @Override public java.util.Optional<MediaDeletionAssetSnapshot> findById(UUID id) { return actual.findById(id); }
            @Override public List<MediaDeletionAssetSnapshot> lockByIds(Set<UUID> ids) { var rows = actual.lockByIds(ids); observed.accept(rows); return rows; }
            @Override public MediaDeletionAssetSnapshot confirmOwnership(UUID id, MediaDeletionOwnership ownership, long version, Instant now) { return actual.confirmOwnership(id, ownership, version, now); }
        };
    }
}
