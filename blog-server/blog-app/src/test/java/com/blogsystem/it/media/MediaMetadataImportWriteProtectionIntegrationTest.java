package com.blogsystem.it.media;

import static org.junit.jupiter.api.Assertions.*;

import com.blogsystem.app.config.cli.MediaCliConfiguration;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportSources;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(40)
class MediaMetadataImportWriteProtectionIntegrationTest {
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("80000000-0000-0000-0000-000000000002");
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();

    // A per-song guard would insert song A before waiting for B, leaving this marker lock visible.
    @Test void importWaitsForEveryResourceBeforeFirstBusinessInsert() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            schema.execute("CREATE FUNCTION mark_import_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(239851014); RETURN NEW; END $$");
            schema.execute("CREATE TRIGGER mark_import_insert BEFORE INSERT ON music_track FOR EACH ROW EXECUTE FUNCTION mark_import_insert()");
            var sources = sources(schema.name(), "https://external.example/cover.png");
            var service = MediaCliConfiguration.importer(environment(), "blog_media_test");
            var pool = Executors.newSingleThreadExecutor();
            try (var blocker = schema.openConnection()) {
                blocker.setAutoCommit(false);
                int blockerPid;
                try (var statement = blocker.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); blockerPid = rows.getInt(1); }
                try (var statement = blocker.prepareStatement("SELECT id FROM media_asset WHERE id=? FOR UPDATE")) { statement.setObject(1, B); statement.executeQuery().close(); }
                var result = pool.submit(() -> service.execute(sources, this::serialize));
                try {
                    int writer = awaitBlockedImporter(schema, blockerPid);
                    try (var c = schema.openConnection(); var s = c.prepareStatement("SELECT count(*) FROM pg_locks WHERE pid=? AND locktype='advisory' AND objid=239851014 AND granted")) {
                        s.setInt(1, writer); try (var rows = s.executeQuery()) { rows.next(); assertEquals(0, rows.getInt(1), "Business INSERT ran before the batch resource locks"); }
                    }
                    blocker.commit();
                    assertEquals(2, json.readTree(result.get(15, TimeUnit.SECONDS)).path("tracksCreated").asInt());
                } finally { blocker.rollback(); }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void unknownManagedCoverRollsBackImport() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            var sources = sources(schema.name(), "https://img.example/file/blog/covers/not-registered.png");
            var failure = assertThrows(MediaImportFailure.class, () -> MediaCliConfiguration.importer(environment(), "blog_media_test").execute(sources, this::serialize));
            assertEquals("REFERENCE_GUARD", failure.stage());
            assertEquals(0, count(schema, "music_track"));
            assertEquals(2, count(schema, "media_asset"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE_PENDING", "DELETE_FAILED", "DELETED"})
    void inactiveAfterPlanningRollsBackWholeImport(String state) throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            var sources = sources(schema.name(), "https://external.example/cover.png");
            blocked(schema, sources, B, (blocker, result) -> {
                try (var s = blocker.prepareStatement("UPDATE media_asset SET status=?,version=version+1 WHERE id=?")) {
                    s.setString(1, state); s.setObject(2, B); assertEquals(1, s.executeUpdate());
                }
                blocker.commit();
                var failure = assertInstanceOf(MediaImportFailure.class,
                        assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(15, TimeUnit.SECONDS)).getCause());
                assertEquals("REFERENCE_GUARD", failure.stage());
                assertEquals(MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED, failure.outcome());
                assertEquals(0, count(schema, "music_track"));
                assertEquals(2, count(schema, "media_asset"));
            });
        }
    }

    @Test void registeredManagedCoverOutsideInventoryIsAlsoLocked() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            UUID cover = UUID.fromString("90000000-0000-0000-0000-000000000003");
            schema.execute("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES ('" + cover + "','cloudflare_imgbed','blog/covers/中文.png','IMAGE','cover.png','ACTIVE',now(),now())");
            var sources = sources(schema.name(), "https://img.example/file/blog/covers/%E4%B8%AD%E6%96%87.png");
            blocked(schema, sources, cover, (blocker, result) -> {
                assertFalse(result.isDone());
                assertEquals(0, count(schema, "music_track"));
                blocker.commit();
                assertEquals(2, json.readTree(result.get(15, TimeUnit.SECONDS)).path("tracksCreated").asInt());
            });
        }
    }

    // Omitting the video UUID from the batch would reach the music INSERT before this wait.
    @ParameterizedTest
    @ValueSource(strings = {"DELETE_PENDING", "DELETE_FAILED", "DELETED"})
    void videoInvalidationRollsBackMixedBatchAndRetryNeverReactivatesIt(String state) throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            var sources = sources(schema.name(), "https://external.example/cover.png");
            UUID video = addVideo(schema, sources);
            schema.execute("CREATE FUNCTION mark_mixed_import() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(239851014); RETURN NEW; END $$");
            schema.execute("CREATE TRIGGER mark_mixed_music BEFORE INSERT ON music_track FOR EACH ROW EXECUTE FUNCTION mark_mixed_import()");
            schema.execute("CREATE TRIGGER mark_mixed_video BEFORE INSERT ON surprise_video FOR EACH ROW EXECUTE FUNCTION mark_mixed_import()");
            blocked(schema, sources, video, (blocker, result) -> {
                try (var c = schema.openConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=239851014 AND granted")) {
                    r.next(); assertEquals(0, r.getInt(1), "Mixed catalog INSERT started before the video resource lock");
                }
                try (var s = blocker.prepareStatement("UPDATE media_asset SET status=?,version=version+1 WHERE id=?")) {
                    s.setString(1, state); s.setObject(2, video); assertEquals(1, s.executeUpdate());
                }
                blocker.commit();
                var failure = assertInstanceOf(MediaImportFailure.class,
                        assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(15, TimeUnit.SECONDS)).getCause());
                assertEquals("REFERENCE_GUARD", failure.stage());
                assertEquals(0, count(schema, "music_track")); assertEquals(0, count(schema, "surprise_video"));
                assertEquals(3, count(schema, "media_asset"), "The newly registered image must roll back too");
            });
            var receipt = json.readTree(MediaCliConfiguration.importer(environment(), "blog_media_test").execute(sources, this::serialize));
            assertEquals(2, receipt.path("tracksCreated").asInt());
            assertEquals(0, receipt.path("videosCreated").asInt()); assertEquals(1, receipt.path("videosSkipped").asInt());
            assertEquals(0, count(schema, "surprise_video"));
            try (var c = schema.openConnection(); var s = c.prepareStatement("SELECT status,version FROM media_asset WHERE id=?")) {
                s.setObject(1, video); try (var r = s.executeQuery()) { assertTrue(r.next()); assertEquals(state, r.getString(1)); assertEquals(1, r.getLong(2)); }
            }
        }
    }

    @Test void concurrentSameIdKeepsWinner() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            var sources = sources(schema.name(), "https://external.example/cover.png");
            blocked(schema, sources, B, (blocker, result) -> {
                schema.execute("INSERT INTO music_track(id,title,artist,enabled,sort_order,version,created_at,updated_at) VALUES ('first','并发编辑胜者','独立作者',false,25,4,now(),now())");
                blocker.commit();
                assertEquals(1, json.readTree(result.get(15, TimeUnit.SECONDS)).path("tracksCreated").asInt());
                try (var c = schema.openConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT title,artist,audio_asset_id,enabled,sort_order,version FROM music_track WHERE id='first'")) {
                    assertTrue(r.next()); assertEquals("并发编辑胜者", r.getString(1)); assertEquals("独立作者", r.getString(2));
                    assertNull(r.getObject(3)); assertFalse(r.getBoolean(4)); assertEquals(25, r.getInt(5)); assertEquals(4, r.getLong(6));
                }
            });
        }
    }

    @Test void existingAndSoftDeletedCatalogRowsArePreserved() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema); MediaDeletionDdlIntegrationTest.migrate(schema);
            var sources = sources(schema.name(), "https://img.example/file/blog/covers/unknown.png");
            schema.execute("INSERT INTO music_track(id,title,artist,audio_asset_id,external_cover_url,enabled,sort_order,version,created_at,updated_at,deleted_at) VALUES ('first','已修改','作者','" + A + "','https://img.example/file/blog/covers/unknown.png',false,8,5,now(),now(),now()),('second','保留资料','作者','" + B + "',NULL,false,9,7,now(),now(),NULL)");
            var video = addVideo(schema, sources);
            schema.execute("INSERT INTO surprise_video(id,title,video_asset_id,enabled,sort_order,version,created_at,updated_at,deleted_at) VALUES ('video','已删除视频','" + video + "',false,8,9,now(),now(),now())");
            schema.execute("UPDATE media_asset SET status='DELETE_PENDING',version=version+1");
            String before = catalogRows(schema);
            var receipt = json.readTree(MediaCliConfiguration.importer(environment(), "blog_media_test").execute(sources, this::serialize));
            assertEquals(0, receipt.path("tracksCreated").asInt()); assertEquals(0, receipt.path("videosCreated").asInt());
            assertEquals(before, catalogRows(schema));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void baseSchemaNeedsNoDeletionColumns(boolean withDeletionSchema) throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema); if (withDeletionSchema) MediaDeletionDdlIntegrationTest.migrate(schema);
            var sources = sources(schema.name(), "https://external.example/cover.png");
            assertEquals(2, json.readTree(MediaCliConfiguration.importer(environment(), "blog_media_test").execute(sources, this::serialize)).path("tracksCreated").asInt());
            try (var c = schema.openConnection(); var s = c.prepareStatement("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='media_asset' AND column_name='deletion_scope'")) {
                s.setString(1, schema.name()); try (var r = s.executeQuery()) { r.next(); assertEquals(withDeletionSchema ? 1 : 0, r.getInt(1)); }
            }
            try (var c = schema.openConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT to_regclass('media_upload_operation'),to_regclass('media_publication_pin')")) {
                r.next(); assertNull(r.getObject(1)); assertNull(r.getObject(2));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oppositeBusinessOrderUsesOneGlobalResourceOrder(boolean reverse) throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            var sources = sources(schema.name(), "https://external.example/cover.png");
            addVideo(schema, sources);
            if (reverse) {
                var songs = (com.fasterxml.jackson.databind.node.ArrayNode) json.readTree(Files.readString(sources.musicCatalog()));
                var first = songs.remove(0); songs.add(first); Files.writeString(sources.musicCatalog(), json.writeValueAsString(songs)); refreshChecksum(sources);
            }
            blocked(schema, sources, A, (blocker, result) -> {
                // A is first globally even when the input starts with B: B must still be free.
                try (var c = schema.openConnection()) {
                    c.setAutoCommit(false);
                    try (var s = c.prepareStatement("SELECT id FROM media_asset WHERE id=? FOR UPDATE NOWAIT")) { s.setObject(1, B); s.executeQuery().close(); }
                    c.rollback();
                }
                blocker.commit();
                var receipt = json.readTree(result.get(15, TimeUnit.SECONDS));
                assertEquals(2, receipt.path("tracksCreated").asInt()); assertEquals(1, receipt.path("videosCreated").asInt());
            });
        }
    }

    @Test @Timeout(90) void locksAllReferencesAcrossThousandBoundary() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            schema.execute("CREATE FUNCTION mark_large_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(239851014); RETURN NEW; END $$");
            schema.execute("CREATE TRIGGER mark_large_insert BEFORE INSERT ON music_track FOR EACH ROW EXECUTE FUNCTION mark_large_insert()");
            var sources = sources(schema.name(), "https://external.example/cover.png");
            var songs = json.createArrayNode(); var objects = json.createArrayNode();
            UUID last = null;
            try (var c = schema.openConnection(); var s = c.prepareStatement("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES (?,'cloudflare_imgbed',?,'AUDIO','item.mp3','ACTIVE',now(),now())")) {
                for (int n = 0; n < 1001; n++) {
                    last = new UUID(0, n + 16); String key = "blog/music/item" + n + ".mp3";
                    s.setObject(1, last); s.setString(2, key); s.addBatch();
                    songs.addObject().put("id", "item" + n).put("title", "歌曲" + n).put("artist", "歌手").put("audioPath", key).put("enabled", true).put("sortOrder", n);
                    objects.addObject().put("kind", "AUDIO").put("objectKey", key).putObject("metadata").put("fileName", "item.mp3");
                }
                s.executeBatch();
            }
            Files.writeString(sources.musicCatalog(), json.writeValueAsString(songs));
            var report = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(sources.report())); report.set("objects", objects);
            Files.writeString(sources.report(), json.writeValueAsString(report)); refreshChecksum(sources);
            blocked(schema, sources, last, (blocker, result) -> {
                try (var c = schema.openConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=239851014 AND granted")) {
                    r.next(); assertEquals(0, r.getInt(1), "First catalog write ran before the last resource lock");
                }
                blocker.commit();
                assertEquals(1001, json.readTree(result.get(45, TimeUnit.SECONDS)).path("tracksCreated").asInt());
                assertEquals(1001, count(schema, "music_track"));
            });
        }
    }

    @Test void resourceLockTimeoutRollsBackAndHidesSql() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            var sources = sources(schema.name(), "https://external.example/cover.png");
            blocked(schema, sources, B, (blocker, result) -> {
                var failure = assertInstanceOf(MediaImportFailure.class,
                        assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(12, TimeUnit.SECONDS)).getCause());
                assertEquals("REFERENCE_GUARD", failure.stage());
                assertEquals(MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED, failure.outcome());
                assertFalse(failure.getMessage().contains("SELECT")); assertFalse(failure.getMessage().contains("blog/music"));
                assertEquals(0, count(schema, "music_track")); assertEquals(2, count(schema, "media_asset"));
            });
        }
    }

    @Test void expiredInputRejectsBeforeWritingAssets() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            seed(schema);
            var sources = sources(schema.name(), "https://external.example/cover.png");
            var report = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(sources.report())); report.put("scanFinishedAt", Instant.now().minusSeconds(90000).toString());
            Files.writeString(sources.report(), json.writeValueAsString(report));
            var failure = assertThrows(MediaImportFailure.class, () -> MediaCliConfiguration.importer(environment(), "blog_media_test").execute(sources, this::serialize));
            assertEquals("INPUT", failure.stage()); assertEquals(2, count(schema, "media_asset")); assertEquals(0, count(schema, "music_track"));
        }
    }

    private UUID addVideo(MediaPostgresTestSupport.ScopedSchema schema, MediaImportSources sources) throws Exception {
        UUID video = UUID.fromString("40000000-0000-0000-0000-000000000003");
        schema.execute("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES ('" + video + "','cloudflare_imgbed','blog/surprise/video.mp4','VIDEO','video.mp4','ACTIVE',now(),now())");
        Files.writeString(sources.surpriseCatalog(), """
            [{"id":"video","title":"视频","url":"https://external.example/video.mp4","path":"blog/surprise/video.mp4","mediaType":"video/mp4","size":10,"enabled":false,"sortOrder":3}]
            """);
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(sources.report()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("snapshots").get(1)).put("available", true).put("sha256", checksum(sources.surpriseCatalog()));
        ((com.fasterxml.jackson.databind.node.ArrayNode) report.path("objects")).addObject().put("kind", "VIDEO").put("objectKey", "blog/surprise/video.mp4").putObject("metadata").put("fileName", "video.mp4");
        Files.writeString(sources.report(), json.writeValueAsString(report));
        return video;
    }

    private String catalogRows(MediaPostgresTestSupport.ScopedSchema schema) throws Exception {
        var result = new StringBuilder();
        try (var c = schema.openConnection(); var s = c.createStatement()) {
            for (var table : java.util.List.of("music_track", "surprise_video")) {
                try (var r = s.executeQuery("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id")) { while (r.next()) result.append(r.getString(1)); }
            }
        }
        return result.toString();
    }

    private static String checksum(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private void refreshChecksum(MediaImportSources sources) throws Exception {
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(sources.report()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("snapshots").get(0)).put("sha256", checksum(sources.musicCatalog()));
        Files.writeString(sources.report(), json.writeValueAsString(report));
    }

    private void blocked(MediaPostgresTestSupport.ScopedSchema schema, MediaImportSources sources,
            UUID resource, BlockedAssertions assertions) throws Exception {
        var service = MediaCliConfiguration.importer(environment(), "blog_media_test");
        var pool = Executors.newSingleThreadExecutor();
        try (var blocker = schema.openConnection()) {
            blocker.setAutoCommit(false);
            int pid;
            try (var s = blocker.createStatement(); var r = s.executeQuery("SELECT pg_backend_pid()")) { r.next(); pid = r.getInt(1); }
            try (var s = blocker.prepareStatement("SELECT id FROM media_asset WHERE id=? FOR UPDATE")) { s.setObject(1, resource); s.executeQuery().close(); }
            var result = pool.submit(() -> service.execute(sources, this::serialize));
            try { awaitBlockedImporter(schema, pid); assertions.run(blocker, result); }
            finally { blocker.rollback(); }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @FunctionalInterface
    private interface BlockedAssertions {
        void run(java.sql.Connection blocker, java.util.concurrent.Future<String> result) throws Exception;
    }

    static Map<String, String> environment() {
        return Map.of("MEDIA_IMPORT_DB_URL", System.getenv("MEDIA_PG_TEST_URL"),
                "MEDIA_IMPORT_DB_USERNAME", System.getenv("MEDIA_PG_TEST_USERNAME"),
                "MEDIA_IMPORT_DB_PASSWORD", System.getenv("MEDIA_PG_TEST_PASSWORD"), "IMGBED_BASE_URL", "https://img.example");
    }

    String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    MediaImportSources sources(String schema, String cover) throws Exception {
        var music = directory.resolve("music.json");
        Files.writeString(music, """
            [{"id":"first","title":"歌曲A","artist":"歌手","sourceId":"163","audioPath":"blog/music/a.mp3","coverPath":"","lyricsPath":"","enabled":true,"sortOrder":0},
             {"id":"second","title":"歌曲B","artist":"歌手","sourceId":"","audioPath":"blog/music/b.mp3","coverPath":"","lyricsPath":"","enabled":false,"sortOrder":1}]
            """);
        var playlist = directory.resolve("playlist.json");
        Files.writeString(playlist, json.writeValueAsString(Map.of("songs", java.util.List.of(Map.of("id", "163", "cover", cover)))));
        var report = directory.resolve("report.json");
        Files.writeString(report, """
            {"mode":"READ_ONLY_DRY_RUN","provider":"cloudflare_imgbed","rootFolder":"blog","scanFinishedAt":"%s",
             "snapshots":[{"source":"music_track","available":true,"sha256":"%s"},{"source":"surprise_video","available":false,"sha256":""}],
             "objects":[{"kind":"AUDIO","objectKey":"blog/music/a.mp3","metadata":{"fileName":"a.mp3"}},
                        {"kind":"AUDIO","objectKey":"blog/music/b.mp3","metadata":{"fileName":"b.mp3"}},
                        {"kind":"IMAGE","objectKey":"blog/covers/new.png","metadata":{"fileName":"new.png"}}]}
            """.formatted(Instant.now(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(music)))));
        return new MediaImportSources("blog_media_test", schema, report, music, directory.resolve("missing-videos.json"), playlist,
                com.blogsystem.test.RepositoryPaths.file("migration", "005_add_media_metadata_tables.sql"), null);
    }

    static void seed(MediaPostgresTestSupport.ScopedSchema schema) throws Exception {
        schema.executeDdl();
        schema.execute("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES ('" + A + "','cloudflare_imgbed','blog/music/a.mp3','AUDIO','a.mp3','ACTIVE',now(),now()),('" + B + "','cloudflare_imgbed','blog/music/b.mp3','AUDIO','b.mp3','ACTIVE',now(),now())");
    }

    static int count(MediaPostgresTestSupport.ScopedSchema schema, String table) throws Exception {
        assertTrue(java.util.Set.of("music_track", "surprise_video", "media_asset").contains(table));
        try (var c = schema.openConnection(); var s = c.createStatement(); var rows = s.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); return rows.getInt(1); }
    }

    static int awaitBlockedImporter(MediaPostgresTestSupport.ScopedSchema schema, int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var c = schema.openConnection(); var s = c.prepareStatement("SELECT pid FROM pg_locks WHERE locktype='advisory' AND objid=239851013 AND granted AND ?=ANY(pg_blocking_pids(pid))")) {
            s.setInt(1, blocker);
            while (System.nanoTime() < deadline) {
                try (var rows = s.executeQuery()) { if (rows.next()) return rows.getInt(1); }
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Expected the import transaction to wait on the held resource");
    }
}
