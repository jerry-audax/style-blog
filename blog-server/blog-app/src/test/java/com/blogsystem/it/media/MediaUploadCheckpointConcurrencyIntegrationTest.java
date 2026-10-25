package com.blogsystem.it.media;

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadExecutionFence;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static com.blogsystem.it.media.MediaUploadOperationRepositoryIntegrationTest.fence;
import static com.blogsystem.it.media.MediaUploadOperationRepositoryIntegrationTest.repo;
import static org.junit.jupiter.api.Assertions.*;

/** M03B2 Review Focus 1: observe real row-lock contention, not three sequential writes. */
class MediaUploadCheckpointConcurrencyIntegrationTest {
    private static final int WAIT_SECONDS = 8;

    enum Action { HEARTBEAT, PAUSE, CHUNK_RECEIPT }

    static Stream<List<Action>> queueOrders() {
        return Stream.of(
                List.of(Action.HEARTBEAT, Action.PAUSE, Action.CHUNK_RECEIPT),
                List.of(Action.HEARTBEAT, Action.CHUNK_RECEIPT, Action.PAUSE),
                List.of(Action.PAUSE, Action.HEARTBEAT, Action.CHUNK_RECEIPT),
                List.of(Action.PAUSE, Action.CHUNK_RECEIPT, Action.HEARTBEAT),
                List.of(Action.CHUNK_RECEIPT, Action.HEARTBEAT, Action.PAUSE),
                List.of(Action.CHUNK_RECEIPT, Action.PAUSE, Action.HEARTBEAT));
    }

    @ParameterizedTest(name = "queued writers {0}")
    @MethodSource("queueOrders")
    @Timeout(45)
    void concurrentPauseHeartbeatAndReceiptPreserveAllThreeFacts(List<Action> order) throws Exception {
        try (var schema = MediaUploadOperationRepositoryIntegrationTest.fixture()) {
            var factory = MediaUploadOperationRepositoryIntegrationTest.factory(schema.name());
            var before = prepareInFlightChunk(schema, factory);
            var executionFence = fence(before);
            var starts = new EnumMap<Action, CountDownLatch>(Action.class);
            var workers = new EnumMap<Action, Future<Instant>>(Action.class);
            var backendPids = new EnumMap<Action, Integer>(Action.class);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
            try {
                try (var blocker = factory.openSession(false); var monitor = schema.openConnection()) {
                    repo(blocker).findOwnedForUpdate(1, before.intent().id()).orElseThrow();
                    for (var action : order) {
                        var start = new CountDownLatch(1);
                        var connected = new CompletableFuture<Integer>();
                        starts.put(action, start);
                        workers.put(action, executor.submit(() -> write(
                                factory, before.intent().id(), executionFence, action, start, connected)));
                        int backendPid = connected.get(WAIT_SECONDS, TimeUnit.SECONDS);
                        backendPids.put(action, backendPid);
                        start.countDown();
                        awaitRowLock(monitor, backendPid);
                    }
                    assertEquals(3, backendPids.values().stream().distinct().count(),
                            "Every writer must have its own PostgreSQL connection");
                    for (var action : order) {
                        assertFalse(workers.get(action).isDone(), "Writer must still be blocked: " + action);
                        assertWaitingOnRowLock(monitor, backendPids.get(action));
                    }
                    blocker.rollback();
                }
                Instant renewedUntil = null;
                for (var action : order) {
                    var result = workers.get(action).get(WAIT_SECONDS * 2L, TimeUnit.SECONDS);
                    if (action == Action.HEARTBEAT) renewedUntil = result;
                }
                assertNotNull(renewedUntil);
                assertPreservedFacts(factory, before, executionFence, renewedUntil);
            } finally {
                starts.values().forEach(CountDownLatch::countDown);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(WAIT_SECONDS * 2L, TimeUnit.SECONDS),
                        "Concurrent writers must finish before their owned schema is dropped");
            }
        }
    }

    private static MediaUploadOperation prepareInFlightChunk(
            MediaPostgresTestSupport.ScopedSchema schema, SqlSessionFactory factory) throws Exception {
        var id = UUID.randomUUID();
        try (var session = factory.openSession(false)) {
            var repository = repo(session);
            repository.insert(MediaUploadOperationRepositoryIntegrationTest.ready(
                    id, 1, 16_000_001, Instant.now()));
            session.commit();
            var claimed = repository.claimContent(1, id, UUID.randomUUID()).orElseThrow();
            session.commit();
            var executionFence = fence(claimed);
            repository.checkpoint(executionFence, new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.INIT, null));
            session.commit();
            repository.checkpoint(executionFence, new MediaUploadCheckpoint.Initialized("contention-session"));
            session.commit();
            repository.checkpoint(executionFence, new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.CHUNK, 0));
            session.commit();
        }
        // A distinguishable, still-valid lease proves that checkpoint/pause did not restore an old expiry.
        schema.execute("UPDATE media_upload_operation SET lease_until=clock_timestamp()+interval '45 seconds'"
                + " WHERE id='" + id + "'");
        try (var session = factory.openSession(false)) {
            return repo(session).findOwned(1, id).orElseThrow();
        }
    }

    private static Instant write(
            SqlSessionFactory factory, UUID id, MediaUploadExecutionFence executionFence,
            Action action, CountDownLatch start, CompletableFuture<Integer> connected) throws Exception {
        try (var session = factory.openSession(false)) {
            try (var statement = session.getConnection().createStatement()) {
                statement.execute("SET LOCAL lock_timeout='8s'");
                statement.execute("SET LOCAL statement_timeout='12s'");
                try (var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertTrue(row.next());
                    connected.complete(row.getInt(1));
                }
            }
            assertTrue(start.await(WAIT_SECONDS, TimeUnit.SECONDS), "Writer start gate timed out");
            var repository = repo(session);
            Instant renewedUntil = null;
            switch (action) {
                case HEARTBEAT -> {
                    assertTrue(repository.renewLease(executionFence), "The current fenced lease must renew");
                    renewedUntil = repository.findOwned(1, id).orElseThrow().lease().until();
                }
                case PAUSE -> requestPauseWithExplicitVersionRetry(session, id);
                case CHUNK_RECEIPT -> repository.checkpoint(executionFence, new MediaUploadCheckpoint.ChunkConfirmed(0));
            }
            session.commit();
            return renewedUntil;
        } catch (Exception | AssertionError failure) {
            connected.completeExceptionally(failure);
            throw failure;
        }
    }

    private static void requestPauseWithExplicitVersionRetry(SqlSession session, UUID id) {
        for (int attempt = 0; attempt < 4; attempt++) {
            var repository = repo(session);
            var version = repository.findOwned(1, id).orElseThrow().version();
            try {
                repository.requestPause(1, id, version);
                return;
            } catch (MediaUploadException failure) {
                session.rollback();
                if (failure.reason() != MediaUploadException.Reason.CONFLICT) throw failure;
            }
        }
        fail("Pause must succeed after rereading at most two competing version increments");
    }

    private static void assertPreservedFacts(
            SqlSessionFactory factory, MediaUploadOperation before,
            MediaUploadExecutionFence executionFence, Instant renewedUntil) {
        try (var session = factory.openSession(false)) {
            var repository = repo(session);
            var after = repository.findOwned(1, before.intent().id()).orElseThrow();
            assertEquals(before.intent(), after.intent());
            assertEquals(before.version() + 3, after.version(), "Each successful writer increments version once");
            assertTrue(after.progress().pauseRequested(), "A chunk receipt must not erase a concurrent pause");
            assertEquals(renewedUntil, after.lease().until(), "A checkpoint must not restore a stale lease");
            assertTrue(renewedUntil.isAfter(before.lease().until().plusSeconds(100)));
            assertEquals(before.lease().owner(), after.lease().owner());
            assertEquals(executionFence.fencingToken(), after.lease().fencingToken());
            assertEquals(MediaUploadStatus.UPLOADING, after.progress().status());
            assertEquals(MediaUploadStage.CHUNK, after.progress().stage());
            assertEquals("contention-session", after.progress().providerUploadId());
            assertEquals(1, after.progress().confirmedChunks());
            assertNull(after.progress().inFlightChunk());
            assertThrows(MediaUploadException.class, () -> repository.checkpoint(
                    executionFence, new MediaUploadCheckpoint.BeginRequest(MediaUploadStage.CHUNK, 1)),
                    "A confirmed pause must prevent dispatching the next chunk");
            session.rollback();
            assertEquals(after, repository.findOwned(1, before.intent().id()).orElseThrow());
        }
    }

    private static void awaitRowLock(Connection monitor, int backendPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (waitingOnRowLock(monitor, backendPid)) return;
            Thread.sleep(10);
        }
        fail("Expected a live PostgreSQL row-lock waiter before releasing the blocker");
    }

    private static void assertWaitingOnRowLock(Connection monitor, int backendPid) throws SQLException {
        assertTrue(waitingOnRowLock(monitor, backendPid), "All three writers must contend before release");
    }

    private static boolean waitingOnRowLock(Connection monitor, int backendPid) throws SQLException {
        try (var statement = monitor.prepareStatement("SELECT wait_event_type='Lock'"
                + " AND cardinality(pg_blocking_pids(pid))>0 FROM pg_stat_activity"
                + " WHERE pid=? AND datname=current_database()")) {
            statement.setQueryTimeout(2);
            statement.setInt(1, backendPid);
            try (var row = statement.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }
}
