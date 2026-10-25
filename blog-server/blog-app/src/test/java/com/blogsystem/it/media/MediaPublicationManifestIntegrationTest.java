package com.blogsystem.it.media;

import static org.junit.jupiter.api.Assertions.*;

import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationManifestInput;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaPublicationManifestRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedReferenceLinkGateway;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

class MediaPublicationManifestIntegrationTest {
    static final MediaPublicationPinKey KEY = new MediaPublicationPinKey("local", "hexo", "manifest-test");
    static final UUID COVER = UUID.fromString(MediaRuntimeRepositoryIntegrationTest.IMAGE);

    @Test void locationChangedBetweenResolutionAndPrepareCannotPinOldIdentity() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            f.jdbc.update("UPDATE media_asset SET object_key='blog/covers/中文.png' WHERE id=?", COVER);
            var manifest = resolver(f).resolve(input("https://img.example/file/blog/covers/%E4%B8%AD%E6%96%87.png"));
            f.jdbc.update("UPDATE media_asset SET object_key='blog/covers/moved.png' WHERE id=?", COVER);
            conflict(() -> f.service.prepareVerified(KEY, manifest));
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @ParameterizedTest @ValueSource(strings={"kind", "provider", "root", "DELETE_PENDING", "DELETE_FAILED", "DELETED"})
    void changedIdentityOrInactiveAssetCannotCreateHeldPin(String change) throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            f.jdbc.update("UPDATE media_asset SET object_key='blog/covers/a.png' WHERE id=?", COVER);
            var manifest = resolver(f).resolve(input("https://old.example/file/blog/covers/a.png"));
            if (change.equals("kind")) f.jdbc.update("UPDATE media_asset SET media_kind='AUDIO' WHERE id=?", COVER);
            else if (change.equals("provider")) f.jdbc.update("UPDATE media_asset SET provider='other' WHERE id=?", COVER);
            else if (change.equals("root")) f.jdbc.update("UPDATE media_asset SET object_key='other/a.png' WHERE id=?", COVER);
            else f.jdbc.update("UPDATE media_asset SET status=? WHERE id=?", change, COVER);
            conflict(() -> f.service.prepareVerified(KEY, manifest));
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
        }
    }

    @Test void aliasesAndDuplicatesResolveOneIdentityAndExternalLinksDoNotBecomeAssets() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            f.jdbc.update("UPDATE media_asset SET object_key='blog/covers/a.png' WHERE id=?", COVER);
            var resolver = resolver(f);
            var first = resolver.resolve(new MediaPublicationManifestInput("a".repeat(64), List.of("https://img.example/file/blog/covers/a.png", "https://external.example/x.png"), List.of(), List.of()));
            var second = resolver.resolve(new MediaPublicationManifestInput("a".repeat(64), List.of("https://old.example/file/blog/covers/a.png", "/file/blog/covers/a.png"), List.of(), List.of()));
            assertEquals(Set.of(COVER), first.resources().stream().map(value -> value.id()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(first.manifestDigest(), second.manifestDigest());
            var pin = f.service.prepareVerified(KEY, first);
            assertEquals(pin, f.service.prepareVerified(KEY, second));
            var changed = resolver.resolve(new MediaPublicationManifestInput("b".repeat(64), List.of("/file/blog/covers/a.png"), List.of(), List.of()));
            conflict(() -> f.service.prepareVerified(KEY, changed));
        }
    }

    @Test void unknownAndAmbiguousManagedLinksAreNotAnEmptyManifest() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var resolver = resolver(f);
            conflict(() -> resolver.resolve(input("/file/blog/covers/missing.png")));
            conflict(() -> resolver.resolve(input("https://img.example/file/blog/%252e%252e/secret.png")));
            conflict(() -> resolver.resolve(input("https://img.example/file/other/a.png")));
        }
    }

    @Test void renderedHtmlAndMarkdownCodeHaveDifferentReferenceSemantics() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            f.jdbc.update("UPDATE media_asset SET object_key='blog/covers/a.png' WHERE id=?", COVER);
            var manifest = resolver(f).resolve(new MediaPublicationManifestInput("a".repeat(64), List.of(),
                    List.of("```html\n<img src='/file/blog/not-real.png'>\n```"),
                    List.of("    <img srcset='/file/blog/covers/a.png 1x, https://old.example/file/blog/covers/a.png 2x'>")));
            assertEquals(Set.of(COVER), f.service.prepareVerified(KEY, manifest).assetIds());
        }
    }

    @Test void emptyManifestStillHasADurableRootAndCanBeRetired() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var manifest = resolver(f).resolve(new MediaPublicationManifestInput("a".repeat(64), List.of(), List.of(), List.of()));
            var held = f.service.prepareVerified(KEY, manifest);
            assertTrue(held.assetIds().isEmpty()); assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
            f.service.release(KEY, 0, held.manifestDigest());
            conflict(() -> f.service.prepareVerified(KEY, manifest));
        }
    }

    @Test @org.junit.jupiter.api.Timeout(30)
    void identityChangedWhilePrepareWaitsForLockCannotBePinned() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture(); var owner = f.schema.openConnection()) {
            f.jdbc.update("UPDATE media_asset SET object_key='blog/covers/a.png' WHERE id=?", COVER);
            var manifest = resolver(f).resolve(input("/file/blog/covers/a.png"));
            owner.setAutoCommit(false);
            int ownerPid;
            try (var s = owner.createStatement(); var r = s.executeQuery("SELECT pg_backend_pid()")) { r.next(); ownerPid = r.getInt(1); }
            try (var s = owner.prepareStatement("UPDATE media_asset SET object_key='blog/covers/moved.png' WHERE id=?")) { s.setObject(1, COVER); s.executeUpdate(); }
            var pid = new java.util.concurrent.atomic.AtomicInteger();
            var tx = new org.springframework.transaction.support.TransactionOperations() {
                public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> callback) {
                    return f.tx.execute(status -> { pid.set(f.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); return callback.doInTransaction(status); });
                }
            };
            var service = new com.blogsystem.application.asset.publication.service.MediaPublicationPinApplicationService(f.pins, f.assets, tx, f.clock);
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var result = pool.submit(() -> assertThrows(MediaPublicationPinException.class, () -> service.prepareVerified(KEY, manifest)));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    if (pid.get() > 0 && Boolean.TRUE.equals(f.jdbc.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))", Boolean.class, ownerPid, pid.get()))) { blocked = true; break; }
                    if (result.isDone()) fail("Prepare did not wait for the resource identity lock");
                    Thread.sleep(10);
                }
                assertTrue(blocked, "Expected actual PostgreSQL lock wait");
                owner.commit();
                assertEquals(MediaPublicationPinException.Reason.CONFLICT, result.get(10, java.util.concurrent.TimeUnit.SECONDS).reason());
                assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM media_publication_pin", Integer.class));
            } finally { owner.rollback(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)); }
        }
    }

    @Test void largeManifestResolvesAndPinsEveryResourceAcrossBatches() throws Exception {
        try (var f = new MediaPublicationPinWorkflowFixture()) {
            var inputs = new java.util.ArrayList<String>(); var ids = new java.util.HashSet<UUID>();
            var rows = new java.util.ArrayList<Object[]>();
            for (int n = 0; n < 1001; n++) {
                UUID id = new UUID(n % 2 == 0 ? 0 : Long.MIN_VALUE, n + 512); ids.add(id);
                String key = "blog/manifest/" + id + ".png"; inputs.add("/file/" + key); rows.add(new Object[]{id, key});
            }
            f.jdbc.batchUpdate("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES (?,'cloudflare_imgbed',?,'IMAGE','image.png','ACTIVE',now(),now())", rows);
            var manifest = resolver(f).resolve(new MediaPublicationManifestInput("a".repeat(64), inputs, List.of(), List.of()));
            assertEquals(ids, f.service.prepareVerified(KEY, manifest).assetIds());
            assertEquals(1001, f.service.get(KEY).assetIds().size());
        }
    }

    static MediaPublicationManifestInput input(String link) {
        return new MediaPublicationManifestInput("a".repeat(64), List.of(link), List.of(), List.of());
    }

    static MybatisMediaPublicationManifestRepository resolver(MediaPublicationPinWorkflowFixture f) throws Exception {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.addMapper(MediaAssetMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(f.jdbc.getDataSource()); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        return new MybatisMediaPublicationManifestRepository(new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class), new MediaAssetConverter()),
                new ImgBedReferenceLinkGateway(Set.of(URI.create("https://img.example"), URI.create("https://old.example")), "blog", 1048576, 10000), "cloudflare_imgbed", new MediaObjectKey("blog"));
    }

    static void conflict(Runnable action) {
        assertEquals(MediaPublicationPinException.Reason.CONFLICT, assertThrows(MediaPublicationPinException.class, action::run).reason());
    }
}
