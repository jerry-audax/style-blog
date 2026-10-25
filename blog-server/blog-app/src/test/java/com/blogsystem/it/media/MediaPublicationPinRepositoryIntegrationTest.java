package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.publication.model.entity.MediaPublicationPin;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaPublicationPinRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaPublicationPinConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaPublicationPinMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaPublicationPinRepositoryIntegrationTest {
    static final Instant NOW = Instant.parse("2026-10-18T01:02:03.123456789Z");
    static final String DIGEST = "b".repeat(64);
    static final MediaPublicationPinKey KEY = new MediaPublicationPinKey("local", "hexo", "Release-1");

    static SqlSessionFactory factory(String schema) throws Exception {
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true); configuration.setDefaultStatementTimeout(10);
        configuration.addMapper(MediaPublicationPinMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setConfiguration(configuration);
        factory.setDataSource(MediaDeletionRepositoryIntegrationTest.dataSource(schema));
        factory.setTransactionFactory(new JdbcTransactionFactory());
        return factory.getObject();
    }
    static MybatisMediaPublicationPinRepository repository(SqlSession session) {
        return new MybatisMediaPublicationPinRepository(session.getMapper(MediaPublicationPinMapper.class), new MediaPublicationPinConverter());
    }

    @Test void roundTripsEmptySingleAndMultipleUuidArraysAndMicroseconds() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            MediaPublicationPinDdlIntegrationTest.migrate(schema);
            try (var session = factory(schema.name()).openSession(false)) {
                var repository = repository(session);
                int i = 0;
                for (Set<UUID> ids : List.of(Set.<UUID>of(), Set.of(UUID.randomUUID()),
                        Set.of(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), UUID.fromString("00000000-0000-0000-0000-000000000001")))) {
                    var pin = MediaPublicationPin.held(new MediaPublicationPinKey("local", "hexo", "r" + i++), DIGEST, ids, NOW);
                    assertEquals(pin, repository.insertHeld(pin)); session.commit();
                    assertEquals(pin, repository.findByKey(pin.key()).orElseThrow());
                    assertEquals(Instant.parse("2026-10-18T01:02:03.123456Z"), pin.createdAt());
                }
                assertTrue(repository.findByKey(KEY).isEmpty());
            }
        }
    }

    @Test void heldLookupIncludesOtherEnvironmentsAndPublishersInStableOrder() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            MediaPublicationPinDdlIntegrationTest.migrate(schema);
            try (var session = factory(schema.name()).openSession(false)) {
                var repository = repository(session); UUID asset = UUID.randomUUID();
                var first = new MediaPublicationPinKey("local", "a", "Release-1");
                var last = new MediaPublicationPinKey("production", "hexo", "Release-1");
                for (var key : List.of(last, KEY, first)) repository.insertHeld(MediaPublicationPin.held(key, DIGEST, Set.of(asset), NOW));
                session.commit();
                assertEquals(List.of(first, KEY, last), repository.findHolding(asset).stream().map(MediaPublicationPin::key).toList());
                var released = repository.release(KEY, 0, NOW.plusSeconds(1)); session.commit();
                assertEquals(MediaPublicationPin.Status.RELEASED, released.status()); assertEquals(1, released.version());
                assertEquals(List.of(first, last), repository.findHolding(asset).stream().map(MediaPublicationPin::key).toList());
                assertTrue(repository.findHolding(UUID.randomUUID()).isEmpty());
            }
        }
    }

    @Test void duplicateConflictsWithoutOverwritingAndInvalidInsertIsRejected() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            MediaPublicationPinDdlIntegrationTest.migrate(schema);
            try (var session = factory(schema.name()).openSession(false)) {
                var repository = repository(session); var original = MediaPublicationPin.held(KEY, DIGEST, Set.of(), NOW);
                repository.insertHeld(original); session.commit();
                assertEquals(MediaPublicationPinException.Reason.CONFLICT, assertThrows(MediaPublicationPinException.class,
                        () -> repository.insertHeld(MediaPublicationPin.held(KEY, "c".repeat(64), Set.of(UUID.randomUUID()), NOW))).reason());
                session.rollback(); assertEquals(original, repository.findByKey(KEY).orElseThrow());
                assertThrows(MediaPublicationPinException.class, () -> repository.insertHeld(original.release(0, NOW)));
                assertThrows(MediaPublicationPinException.class, () -> repository.insertHeld(new MediaPublicationPin(KEY, DIGEST, Set.of(), MediaPublicationPin.Status.HELD, 7, NOW, NOW, null)));
                assertEquals(original, repository.findByKey(KEY).orElseThrow());
            }
        }
    }

    @Test void concurrentReleaseHasOneWinnerAndRollbackDoesNotReleaseOtherPins() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            MediaPublicationPinDdlIntegrationTest.migrate(schema); var factory = factory(schema.name());
            var other = new MediaPublicationPinKey("production", "hexo", "Other");
            try (var session = factory.openSession(false)) {
                repository(session).insertHeld(MediaPublicationPin.held(KEY, DIGEST, Set.of(), NOW));
                repository(session).insertHeld(MediaPublicationPin.held(other, DIGEST, Set.of(), NOW)); session.commit();
            }
            var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
            try {
                var jobs = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 2; i++) jobs.add(pool.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try (var session = factory.openSession(false)) {
                        try { repository(session).release(KEY, 0, NOW.plusSeconds(1)); session.commit(); return true; }
                        catch (MediaPublicationPinException failure) { session.rollback(); assertEquals(MediaPublicationPinException.Reason.CONFLICT, failure.reason()); return false; }
                    }
                }));
                start.countDown(); assertFalse(jobs.get(0).get(10, TimeUnit.SECONDS).equals(jobs.get(1).get(10, TimeUnit.SECONDS)));
                try (var session = factory.openSession(false)) {
                    var repository = repository(session);
                    assertEquals(1, repository.findByKey(KEY).orElseThrow().version());
                    repository.release(other, 0, NOW.plusSeconds(1)); session.rollback();
                    assertEquals(MediaPublicationPin.Status.HELD, repository.findByKey(other).orElseThrow().status());
                    assertThrows(MediaPublicationPinException.class, () -> repository.release(other, Long.MAX_VALUE, NOW));
                    assertThrows(MediaPublicationPinException.class, () -> repository.release(other, 0, NOW.minusSeconds(1)));
                }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }
}
