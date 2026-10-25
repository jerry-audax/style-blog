package com.blogsystem.it.media;

import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;

import com.blogsystem.it.media.MediaPostgresTestSupport.ScopedSchema;

import org.junit.jupiter.api.Assertions;
import org.mybatis.spring.SqlSessionFactoryBean;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetPage;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetQuery;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.model.valobj.DuplicateMediaAssetException;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetWriteConflictException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.mybatis.spring.SqlSessionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaAssetRepositoryIntegrationTest {
    private static final Instant BASE_TIME = Instant.parse("2026-10-10T12:00:00.123456789Z");

    @Test
    void springSqlSessionTranslatesUniqueKeyToDomainDuplicate() throws Exception {
        try (MediaPostgresTestSupport.ScopedSchema schema = MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();
            var template = new SqlSessionTemplate(openFactory(schema.name()));
            MediaAssetRepository repository = new MybatisMediaAssetRepository(
                    template.getMapper(MediaAssetMapper.class), new MediaAssetConverter());
            MediaAsset value = asset("00000000-0000-0000-0000-000000000199", "blog/audio/template-duplicate.mp3",
                    new MediaMetadata("template-duplicate.mp3", "audio/mpeg", 3L, null, null, null, null), BASE_TIME);

            repository.insert(value);

            assertThrows(DuplicateMediaAssetException.class, () -> repository.insert(value));
        }
    }

    @Test
    void roundTripsUuidTimeNullAndZeroMetadataAndRejectsDuplicates() throws Exception {
        try (MediaPostgresTestSupport.ScopedSchema schema = MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();
            try (SqlSession session = openFactory(schema.name()).openSession(false)) {
                MediaAssetRepository repository = repository(session);
                MediaAsset unknown = asset("00000000-0000-0000-0000-000000000101", "blog/music/unknown.mp3",
                        new MediaMetadata("unknown.mp3", null, null, null, null, null, null), BASE_TIME);
                MediaAsset zero = asset("00000000-0000-0000-0000-000000000102", "blog/music/zero.mp3",
                        new MediaMetadata("zero.mp3", "audio/mpeg", 0L, null, null, null, 0L), BASE_TIME.plusSeconds(1));

                repository.insert(unknown);
                repository.insert(zero);
                session.commit();

                MediaAsset loaded = repository.findById(unknown.id()).orElseThrow();
                assertEquals(unknown.id(), loaded.id());
                assertEquals(unknown.objectKey(), loaded.objectKey());
                assertEquals(unknown.createdAt(), loaded.createdAt());
                assertEquals(unknown.metadata(), loaded.metadata());
                assertEquals(0L, loaded.version());
                assertTrue(loaded.metadata().fileSize() == null);
                assertTrue(loaded.metadata().durationMs() == null);
                assertEquals(0L, repository.findById(zero.id()).orElseThrow().metadata().fileSize());
                assertTrue(repository.findByLocation(unknown.provider(), unknown.objectKey()).isPresent());
                assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
                assertThrows(DuplicateMediaAssetException.class, () -> repository.insert(unknown));
                assertThrows(DuplicateMediaAssetException.class, () -> repository.insert(
                        asset("00000000-0000-0000-0000-000000000103", unknown.objectKey().value(),
                                unknown.metadata(), BASE_TIME.plusSeconds(2))));
            }
        }
    }

    @Test
    void updatesOnlyMetadataWithOptimisticVersionAndRejectsStaleWriter() throws Exception {
        try (MediaPostgresTestSupport.ScopedSchema schema = MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();
            try (SqlSession session = openFactory(schema.name()).openSession(false)) {
                MediaAssetRepository repository = repository(session);
                MediaAsset original = asset("00000000-0000-0000-0000-000000000104", "blog/music/edit.mp3",
                        new MediaMetadata("edit.mp3", null, null, null, null, null, null), BASE_TIME);
                repository.insert(original);
                session.commit();
                MediaAsset candidate = original.reviseMetadata(
                        new MediaMetadata("edited.mp3", "audio/mpeg", 10L, null, null, null, 200L),
                        BASE_TIME.plusSeconds(1));

                MediaAsset updated = repository.updateMetadata(candidate, 0);
                assertEquals(1L, updated.version());
                assertEquals(original.id(), updated.id());
                assertEquals(original.provider(), updated.provider());
                assertEquals(original.objectKey(), updated.objectKey());
                assertEquals(original.kind(), updated.kind());
                assertEquals(original.status(), updated.status());
                assertEquals(original.createdAt(), updated.createdAt());
                assertEquals(candidate.metadata(), updated.metadata());
                session.commit();

                assertThrows(MediaAssetWriteConflictException.class,
                        () -> repository.updateMetadata(candidate, 0));
            }
        }
    }

    @Test
    void pagesUseStableCreatedDescendingThenUuidAscendingOrderingAndFilters() throws Exception {
        try (MediaPostgresTestSupport.ScopedSchema schema = MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();
            try (SqlSession session = openFactory(schema.name()).openSession(false)) {
                MediaAssetRepository repository = repository(session);
                MediaAsset first = asset("00000000-0000-0000-0000-000000000105", "blog/images/first.png",
                        new MediaMetadata("first.png", "image/png", null, null, 10, 10, null), BASE_TIME);
                MediaAsset second = asset("00000000-0000-0000-0000-000000000106", "blog/images/second.png",
                        new MediaMetadata("second.png", "image/png", null, null, 10, 10, null), BASE_TIME.plusSeconds(1));
                MediaAsset third = asset("00000000-0000-0000-0000-000000000107", "blog/audio/third.mp3",
                        new MediaMetadata("third.mp3", "audio/mpeg", null, null, null, null, null), BASE_TIME.plusSeconds(2));
                MediaAsset fourth = asset("00000000-0000-0000-0000-000000000108", "blog/audio/fourth.mp3",
                        new MediaMetadata("fourth.mp3", "audio/mpeg", null, null, null, null, null), BASE_TIME.plusSeconds(2));
                repository.insert(first);
                repository.insert(second);
                repository.insert(third);
                repository.insert(fourth);
                session.commit();

                MediaAssetPage page = repository.findPage(new MediaAssetQuery(null, null, 1, 2));
                assertEquals(4L, page.total());
                assertEquals(List.of(fourth.id(), second.id()), page.records().stream().map(MediaAsset::id).toList());

                MediaAssetPage audio = repository.findPage(new MediaAssetQuery(MediaKind.AUDIO, MediaAssetStatus.ACTIVE, 0, 10));
                assertEquals(2L, audio.total());
                assertEquals(List.of(third.id(), fourth.id()), audio.records().stream().map(MediaAsset::id).toList());
            }
        }
    }

    @Test
    void twoWritersUsingSameVersionProduceOneSuccessAndOneConflict() throws Exception {
        try (MediaPostgresTestSupport.ScopedSchema schema = MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();
            MediaAsset original;
            try (SqlSession setup = openFactory(schema.name()).openSession(false)) {
                MediaAssetRepository repository = repository(setup);
                original = asset("00000000-0000-0000-0000-000000000109", "blog/audio/concurrent.mp3",
                        new MediaMetadata("concurrent.mp3", null, null, null, null, null, null), BASE_TIME);
                repository.insert(original);
                setup.commit();
            }

            SqlSession left = openFactory(schema.name()).openSession(false);
            SqlSession right = openFactory(schema.name()).openSession(false);
            MediaAsset leftCandidate = original.reviseMetadata(
                    new MediaMetadata("left.mp3", "audio/mpeg", 1L, null, null, null, 1L), BASE_TIME.plusSeconds(1));
            MediaAsset rightCandidate = original.reviseMetadata(
                    new MediaMetadata("right.mp3", "audio/mpeg", 2L, null, null, null, 2L), BASE_TIME.plusSeconds(1));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> leftResult = executor.submit(() -> updateAndCommit(left, leftCandidate, ready, start));
                Future<Boolean> rightResult = executor.submit(() -> updateAndCommit(right, rightCandidate, ready, start));
                ready.await();
                start.countDown();
                assertTrue(leftResult.get() ^ rightResult.get());
            } finally {
                executor.shutdownNow();
                left.close();
                right.close();
            }

            try (SqlSession verify = openFactory(schema.name()).openSession(false)) {
                MediaAsset result = repository(verify).findById(original.id()).orElseThrow();
                assertEquals(1L, result.version());
                assertTrue(result.metadata().fileName().equals("left.mp3")
                        || result.metadata().fileName().equals("right.mp3"));
            }
        }
    }

    private static boolean updateAndCommit(SqlSession session, MediaAsset candidate,
                                           CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            repository(session).updateMetadata(candidate, 0);
            session.commit();
            return true;
        } catch (MediaAssetWriteConflictException expected) {
            session.rollback();
            return false;
        } catch (Exception failure) {
            session.rollback();
            throw new RuntimeException(failure);
        }
    }

    private static MediaAsset asset(String id, String objectKey, MediaMetadata metadata, Instant timestamp) {
        MediaKind kind = objectKey.contains("/images/") ? MediaKind.IMAGE : MediaKind.AUDIO;
        return MediaAsset.register(UUID.fromString(id), "cloudflare_imgbed",
                new MediaObjectKey(objectKey), kind, metadata, timestamp);
    }

    private static MediaAssetRepository repository(SqlSession session) {
        return new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class),
                new MediaAssetConverter());
    }

    private static SqlSessionFactory openFactory(String schema) {
        var dataSource = new DriverManagerDataSource(System.getenv("MEDIA_PG_TEST_URL") + "?currentSchema=" + schema,
                System.getenv("MEDIA_PG_TEST_USERNAME"), System.getenv("MEDIA_PG_TEST_PASSWORD"));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MediaAssetMapper.class);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        try {
            return factory.getObject();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create test MyBatis factory", failure);
        }
    }
}
