package com.blogsystem.it.media;

import static org.junit.jupiter.api.Assertions.*;

import com.blogsystem.application.asset.upload.port.UploadBindingPreparation;
import com.blogsystem.application.asset.upload.service.MediaUploadFinalizationService;
import com.blogsystem.app.it.application.asset.upload.service.FakeMediaUploadStorageRepository;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.model.valobj.DuplicateMediaAssetException;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.*;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@Timeout(35)
class UploadFinalizationWriteProtectionIntegrationTest {
    @Test void imageRegistrationOutsideTransactionFailsClosed() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var request = MediaImageUploadWorkflowIntegrationTest.request(
                    com.blogsystem.domain.asset.image.model.valobj.ImagePurpose.ARTICLE,
                    MediaImageUploadWorkflowIntegrationTest.PNG);
            var operation = MediaImageUploadWorkflowIntegrationTest.service(f).create(1, request).operation();
            var file = operation.intent().file();
            var asset = com.blogsystem.domain.asset.metadata.model.entity.MediaAsset.register(
                    operation.intent().candidateAssetId(), "cloudflare_imgbed",
                    FakeMediaUploadStorageRepository.receipt(operation.intent()).objectKey(),
                    com.blogsystem.domain.asset.metadata.model.valobj.MediaKind.IMAGE,
                    new com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata(file.fileName(), file.mediaType(),
                            file.size(), file.sourceSha256(), null, null, null), java.time.Instant.now());
            f.tx.execute(s -> f.assets.insert(asset));
            var snapshot = new MediaUploadResourceSnapshot(asset.id(), asset.provider(), asset.objectKey(), asset.kind(),
                    asset.status(), asset.version(), asset.metadata());
            var prepared = new com.blogsystem.application.asset.image.service.ImageUploadBindingApplicationService(f.assets, f.references)
                    .prepareCommit(operation.intent());
            assertEquals(MediaUploadException.Reason.UNAVAILABLE, assertThrows(MediaUploadException.class,
                    () -> prepared.commit(snapshot, java.time.Instant.now())).reason());
        }
    }

    @Test void finalizerLocksResourcesBeforeOperation() throws Exception {
        try (var f = new MediaUploadWorkflowFixture(); var owner = f.schema.openConnection()) {
            var uploaded = uploaded(f);
            var jdbc = new JdbcTemplate(f.dataSource);
            owner.setAutoCommit(false);
            int ownerPid;
            try (var statement = owner.createStatement()) {
                try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); ownerPid = rows.getInt(1); }
                statement.executeQuery("SELECT id FROM media_asset WHERE id='" + MediaRuntimeRepositoryIntegrationTest.AUDIO + "' FOR UPDATE").close();
            }
            var pid = new AtomicInteger();
            var transactions = new TransactionOperations() {
                public <T> T execute(TransactionCallback<T> callback) {
                    return f.tx.execute(status -> {
                        pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        return callback.doInTransaction(status);
                    });
                }
            };
            var pool = Executors.newSingleThreadExecutor();
            try {
                var result = pool.submit(() -> finalizer(f, transactions).finalizeUpload(fence(uploaded), prepared(f, uploaded)));
                awaitBlocked(jdbc, pid, ownerPid, result);
                // 若主事务已先锁operation，此连接会超时，不能把资源等待误认为正确锁序。
                try (var probe = f.schema.openConnection(); var query = probe.createStatement()) {
                    probe.setAutoCommit(false);
                    query.execute("SET LOCAL lock_timeout='750ms'");
                    try (var rows = query.executeQuery("SELECT id FROM media_upload_operation WHERE id='" + uploaded.intent().id() + "' FOR UPDATE")) {
                        assertTrue(rows.next());
                    }
                    probe.rollback();
                }
                owner.commit();
                assertEquals(MediaUploadStatus.SUCCEEDED, result.get(10, TimeUnit.SECONDS).progress().status());
                assertEquals(8, f.music.findSnapshotById(uploaded.intent().target().id()).orElseThrow().version());
            } finally {
                owner.rollback();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void staleFenceAfterBusinessCasRollsBackEverything() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var uploaded = uploaded(f);
            var real = prepared(f, uploaded);
            var afterCas = new CountDownLatch(1);
            var proceed = new CountDownLatch(1);
            UploadBindingPreparation delayed = (asset, now) -> {
                var result = real.commit(asset, now);
                afterCas.countDown();
                await(proceed);
                return result;
            };
            var pool = Executors.newSingleThreadExecutor();
            try {
                var running = pool.submit(() -> finalizer(f, f.tx).finalizeUpload(fence(uploaded), delayed));
                assertTrue(afterCas.await(10, TimeUnit.SECONDS));
                try (var takeover = f.schema.openConnection(); var query = takeover.createStatement()) {
                    query.execute("SET lock_timeout='750ms'");
                    assertEquals(1, query.executeUpdate("UPDATE media_upload_operation SET fencing_token=fencing_token+1,"
                            + "lease_owner='" + UUID.randomUUID() + "',version=version+1 WHERE id='" + uploaded.intent().id() + "'"));
                }
                // 暂存的业务CAS在提交前不能对其他连接可见。
                assertEquals(7, f.music.findSnapshotById(uploaded.intent().target().id()).orElseThrow().version());
                assertTrue(f.assets.findById(uploaded.intent().candidateAssetId()).isEmpty());
                proceed.countDown();
                var failure = assertThrows(ExecutionException.class, () -> running.get(10, TimeUnit.SECONDS));
                var safe = assertInstanceOf(MediaUploadException.class, failure.getCause());
                assertEquals(MediaUploadErrorCode.LEASE_LOST, safe.code());
                assertEquals(7, f.music.findSnapshotById(uploaded.intent().target().id()).orElseThrow().version());
                assertTrue(f.assets.findById(uploaded.intent().candidateAssetId()).isEmpty());
                var durable = f.operations.findOwned(1, uploaded.intent().id()).orElseThrow();
                assertEquals(MediaUploadStatus.UPLOADED, durable.progress().status());
                assertNull(durable.outcome().result());
                assertEquals(List.of(), f.storage.calls);
            } finally {
                proceed.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void candidateIdentityAndLocationCollisionsNeverBind() throws Exception {
        for (String collision : List.of("id", "location", "metadata")) {
            try (var f = new MediaUploadWorkflowFixture()) {
                var uploaded = uploaded(f);
                var key = FakeMediaUploadStorageRepository.receipt(uploaded.intent()).objectKey();
                var id = collision.equals("location") ? UUID.randomUUID() : uploaded.intent().candidateAssetId();
                var objectKey = collision.equals("id")
                        ? new MediaObjectKey(key.value() + ".other") : key;
                var fileName = collision.equals("metadata") ? "different.mp3" : uploaded.intent().file().fileName();
                f.tx.execute(s -> f.assets.insert(candidate(uploaded, id, objectKey, fileName)));

                var result = finalizer(f, f.tx).finalizeUpload(fence(uploaded), prepared(f, uploaded));

                assertEquals(MediaUploadStatus.CONFLICT, result.progress().status(), collision);
                assertEquals(7, f.music.findSnapshotById(uploaded.intent().target().id()).orElseThrow().version());
                assertTrue(f.assets.findById(id).isPresent());
                assertNull(result.outcome().result());
                assertEquals(List.of(), f.storage.calls);
            }
        }
    }

    @Test void duplicateCandidateInsertReturnsConflictAfterTransactionRollback() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var uploaded = uploaded(f);
            var failingAssets = org.mockito.Mockito.spy(f.assets);
            org.mockito.Mockito.doThrow(new DuplicateMediaAssetException("duplicate", null))
                    .when(failingAssets).insert(org.mockito.ArgumentMatchers.any());
            var finalizer = new MediaUploadFinalizationService(f.operations, failingAssets, f.tx,
                    Clock.systemUTC(), f.urls);

            var result = finalizer.finalizeUpload(fence(uploaded), prepared(f, uploaded));

            assertEquals(MediaUploadStatus.CONFLICT, result.progress().status());
            assertEquals(7, f.music.findSnapshotById(uploaded.intent().target().id()).orElseThrow().version());
            assertTrue(f.assets.findById(uploaded.intent().candidateAssetId()).isEmpty());
            assertNull(result.outcome().result());
            assertEquals(List.of(), f.storage.calls);
        }
    }

    // Both real PG transactions must observe absence before either INSERT hits the unique index.
    @Test void twoFinalizersRaceOnAbsentCandidateAndOnlyOneAssetAndBindingCommit() throws Exception {
        try (var f = new MediaUploadWorkflowFixture()) {
            var operation = uploaded(f);
            var barrier = new CyclicBarrier(2);
            var pids = ConcurrentHashMap.<Integer>newKeySet();
            var duplicateFailures = new AtomicInteger();
            var jdbc = new JdbcTemplate(f.dataSource);
            var assetPort = org.mockito.Mockito.spy(f.assets);
            org.mockito.Mockito.doAnswer(invocation -> {
                MediaAsset candidate = invocation.getArgument(0);
                assertTrue(f.assets.findById(candidate.id()).isEmpty());
                assertTrue(f.assets.findByLocation(candidate.provider(), candidate.objectKey()).isEmpty());
                pids.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                barrier.await(10, TimeUnit.SECONDS);
                try { return invocation.callRealMethod(); }
                catch (DuplicateMediaAssetException duplicate) {
                    boolean uniqueViolation = false;
                    for (Throwable cause = duplicate; cause != null; cause = cause.getCause())
                        if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) uniqueViolation = true;
                    assertTrue(uniqueViolation, "The conflict must originate at the real PG unique index");
                    duplicateFailures.incrementAndGet();
                    throw duplicate;
                }
            }).when(assetPort).insert(org.mockito.ArgumentMatchers.any());
            var finalizer = new MediaUploadFinalizationService(f.operations, assetPort, f.tx, Clock.systemUTC(), f.urls);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> finalizer.finalizeUpload(fence(operation), prepared(f, operation)));
                var second = pool.submit(() -> finalizer.finalizeUpload(fence(operation), prepared(f, operation)));
                var winner = first.get(20, TimeUnit.SECONDS); var replay = second.get(20, TimeUnit.SECONDS);
                assertEquals(2, pids.size()); assertEquals(1, duplicateFailures.get());
                assertEquals(MediaUploadStatus.SUCCEEDED, winner.progress().status());
                assertEquals(MediaUploadStatus.SUCCEEDED, replay.progress().status());
                assertEquals(winner.outcome().result(), replay.outcome().result());
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM media_asset WHERE id=?", Integer.class, operation.intent().candidateAssetId()));
                var track = f.music.findSnapshotById(operation.intent().target().id()).orElseThrow();
                assertEquals(8, track.version()); assertEquals(operation.intent().candidateAssetId(), track.track().audioAssetId());
                assertEquals(0, f.assets.findById(operation.intent().candidateAssetId()).orElseThrow().version());
                assertEquals(List.of(), f.storage.calls);
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void candidateMetadataChangedWhileResourceLockWaitsRollsBackBinding() throws Exception {
        try (var f = new MediaUploadWorkflowFixture(); var owner = f.schema.openConnection()) {
            var uploaded = uploaded(f);
            var key = FakeMediaUploadStorageRepository.receipt(uploaded.intent()).objectKey();
            f.tx.execute(s -> f.assets.insert(candidate(uploaded, uploaded.intent().candidateAssetId(), key,
                    uploaded.intent().file().fileName())));
            owner.setAutoCommit(false);
            int ownerPid;
            try (var statement = owner.createStatement()) {
                try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); ownerPid = rows.getInt(1); }
                statement.executeUpdate("UPDATE media_asset SET file_name='changed-after-upload.mp3' WHERE id='"
                        + uploaded.intent().candidateAssetId() + "'");
            }
            var jdbc = new JdbcTemplate(f.dataSource);
            var pid = new AtomicInteger();
            var transactions = recordingTransactions(f, jdbc, pid);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var result = pool.submit(() -> finalizer(f, transactions).finalizeUpload(fence(uploaded), prepared(f, uploaded)));
                awaitBlocked(jdbc, pid, ownerPid, result);
                assertOperationUnlocked(f, uploaded.intent().id());
                owner.commit();

                var failed = result.get(10, TimeUnit.SECONDS);
                assertEquals(MediaUploadStatus.CONFLICT, failed.progress().status());
                assertEquals(7, f.music.findSnapshotById(uploaded.intent().target().id()).orElseThrow().version());
                assertEquals("changed-after-upload.mp3", f.assets.findById(uploaded.intent().candidateAssetId())
                        .orElseThrow().metadata().fileName());
                assertNull(f.operations.findOwned(1, uploaded.intent().id()).orElseThrow().outcome().result());
                assertEquals(List.of(), f.storage.calls);
            } finally {
                owner.rollback();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void sourceAndChildOperationsRemainUnlockedUntilResourceBindingIsStaged() throws Exception {
        try (var f = new MediaUploadWorkflowFixture(); var owner = f.schema.openConnection()) {
            var service = f.service();
            var request = MediaUploadWorkflowFixture.request(1, MediaUploadSlot.AUDIO, 7);
            service.create(1, request);
            f.tx.execute(s -> f.music.updateMetadata(request.targetId(),
                    new com.blogsystem.domain.music.model.MusicTrackMetadata("changed", "", null, true, 0),
                    7, java.time.Instant.now()));
            var source = service.content(1, request.operationId(), MediaUploadWorkflowFixture.zeros(1));
            assertEquals(MediaUploadStatus.CONFLICT, source.progress().status());

            UUID childId = UUID.randomUUID();
            var childRequest = new com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest(childId,
                    MediaUploadTargetType.MUSIC_TRACK, request.targetId(), MediaUploadSlot.AUDIO, 8L,
                    request.file(), null);
            var target = f.ports.get(0).prepareTarget(childRequest, source.intent().candidateAssetId());
            var child = com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy.binding(1, source, childId,
                    target, source.version(), java.time.Instant.now());
            f.tx.execute(s -> f.operations.insert(child));
            var claimedChild = f.tx.execute(s -> f.operations.claimRecovery(1, childId, child.version(),
                    UUID.randomUUID()).orElseThrow());

            owner.setAutoCommit(false);
            int ownerPid;
            try (var statement = owner.createStatement()) {
                try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); ownerPid = rows.getInt(1); }
                statement.executeQuery("SELECT id FROM media_asset WHERE id='" + MediaRuntimeRepositoryIntegrationTest.AUDIO
                        + "' FOR UPDATE").close();
            }
            var jdbc = new JdbcTemplate(f.dataSource);
            var pid = new AtomicInteger();
            var transactions = recordingTransactions(f, jdbc, pid);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var result = pool.submit(() -> finalizer(f, transactions).finalizeUpload(fence(claimedChild), prepared(f, claimedChild)));
                awaitBlocked(jdbc, pid, ownerPid, result);
                assertOperationUnlocked(f, source.intent().id());
                assertOperationUnlocked(f, child.intent().id());
                owner.commit();

                var done = result.get(10, TimeUnit.SECONDS);
                assertEquals(MediaUploadStatus.SUCCEEDED, done.progress().status());
                assertEquals(9, f.music.findSnapshotById(request.targetId()).orElseThrow().version());
                assertEquals(childId, f.operations.findOwned(1, source.intent().id()).orElseThrow()
                        .outcome().resolvedByOperationId());
                assertEquals(List.of("direct"), f.storage.calls);
            } finally {
                owner.rollback();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    static MediaUploadOperation uploaded(MediaUploadWorkflowFixture f) throws Exception {
        var request = MediaUploadWorkflowFixture.request(1, MediaUploadSlot.AUDIO, 7);
        f.service().create(1, request);
        var claim = f.tx.execute(s -> f.operations.claimContent(1, request.operationId(), UUID.randomUUID()).orElseThrow());
        var fence = fence(claim);
        f.tx.execute(s -> f.operations.checkpoint(fence, new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.DIRECT, null)));
        return f.tx.execute(s -> f.operations.checkpoint(fence,
                new MediaUploadCheckpoint.Receipt(FakeMediaUploadStorageRepository.receipt(claim.intent()).objectKey())));
    }

    static MediaUploadExecutionFence fence(MediaUploadOperation operation) {
        return new MediaUploadExecutionFence(operation.intent().id(), operation.intent().actorId(),
                operation.lease().owner(), operation.lease().fencingToken());
    }

    static UploadBindingPreparation prepared(MediaUploadWorkflowFixture f, MediaUploadOperation operation) {
        return f.ports.get(0).prepareCommit(operation.intent());
    }

    static MediaUploadFinalizationService finalizer(MediaUploadWorkflowFixture f, TransactionOperations tx) {
        return new MediaUploadFinalizationService(f.operations, f.assets, tx, Clock.systemUTC(), f.urls);
    }

    static MediaAsset candidate(MediaUploadOperation operation, UUID id, MediaObjectKey key, String fileName) {
        var file = operation.intent().file();
        return MediaAsset.register(id, operation.intent().provider(), key, MediaKind.AUDIO,
                new MediaMetadata(fileName, file.mediaType(), file.size(), file.sourceSha256(), null, null, null),
                java.time.Instant.now());
    }

    static TransactionOperations recordingTransactions(MediaUploadWorkflowFixture f, JdbcTemplate jdbc,
            AtomicInteger pid) {
        return new TransactionOperations() {
            public <T> T execute(TransactionCallback<T> callback) {
                return f.tx.execute(status -> {
                    pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return callback.doInTransaction(status);
                });
            }
        };
    }

    static void assertOperationUnlocked(MediaUploadWorkflowFixture f, UUID id) throws Exception {
        try (var probe = f.schema.openConnection(); var query = probe.createStatement()) {
            probe.setAutoCommit(false);
            query.execute("SET LOCAL lock_timeout='750ms'");
            try (var rows = query.executeQuery("SELECT id FROM media_upload_operation WHERE id='" + id + "' FOR UPDATE")) {
                assertTrue(rows.next());
            }
            probe.rollback();
        }
    }

    static void awaitBlocked(JdbcTemplate jdbc, AtomicInteger pid, int ownerPid, Future<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0 && Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))", Boolean.class, ownerPid, pid.get()))) return;
            if (future.isDone()) { future.get(); fail("Finalizer did not wait for the protected asset"); }
            Thread.sleep(20);
        }
        fail("No PostgreSQL resource-lock blocking evidence");
    }

    static void await(CountDownLatch signal) {
        try { assertTrue(signal.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
