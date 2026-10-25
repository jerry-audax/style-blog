package com.blogsystem.it.media;

import com.blogsystem.it.media.MediaPostgresTestSupport.ScopedSchema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;

import com.blogsystem.app.cli.MediaMetadataImportMain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MediaMetadataImportIntegrationTest {
    @Test void importReceiptSerializationFailureRollsBackAllWrites() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            Path report = fixture("");
            var service = com.blogsystem.app.config.cli.MediaCliConfiguration.importer(environment(), "blog_media_test");
            var sources = new com.blogsystem.domain.asset.inventory.model.valobj.MediaImportSources(
                    "blog_media_test", schema.name(), report, directory.resolve("music-catalog.json"),
                    directory.resolve("surprise-videos.json"), directory.resolve("public-playlist.json"),
                    com.blogsystem.test.RepositoryPaths.file("migration", "005_add_media_metadata_tables.sql"), null);
            var serializationAttempts = new java.util.concurrent.atomic.AtomicInteger();
            var failure = assertThrows(com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure.class,
                    () -> service.execute(sources, receipt -> {
                        serializationAttempts.incrementAndGet();
                        assertEquals(3, receipt.assetsCreated());
                        assertEquals(1, receipt.tracksCreated());
                        throw new IllegalStateException("fake receipt failure");
                    }));
            assertEquals(1, serializationAttempts.get());
            assertEquals("VIDEO", failure.stage());
            assertEquals(com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED,
                    failure.outcome());
            assertEquals(0, tableCount(schema));
        }
    }
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();

    @Test void createsTablesImportsResourcesAndPreservesExistingEditsOnRerun() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            Path report=fixture("");
            var first=run(schema.name(),report,"blog_media_test");
            assertEquals(0,first.code,first.error);
            assertEquals(3,first.body.path("assetsCreated").asInt());
            assertEquals(1,first.body.path("tracksCreated").asInt());
            assertEquals(0,first.body.path("videosCreated").asInt());
            assertEquals(1,first.body.path("tablesInitialized").asInt());
            String assetId;
            try(var connection=schema.openConnection(); var statement=connection.createStatement()) {
                try(var rows=statement.executeQuery("SELECT audio_asset_id,title,enabled,sort_order,external_cover_url FROM music_track WHERE id='legacy-id'")) {
                    assertTrue(rows.next());assetId=rows.getString(1);assertNotNull(assetId);
                    assertEquals("原始歌曲",rows.getString(2));assertFalse(rows.getBoolean(3));assertEquals(7,rows.getInt(4));
                    assertEquals("https://example.test/cover.jpg",rows.getString(5));
                }
                try(var rows=statement.executeQuery("SELECT file_size, duration_ms FROM media_asset WHERE object_key='blog/music/a.mp3'")) {
                    assertTrue(rows.next());assertNull(rows.getObject(1));assertNull(rows.getObject(2));
                }
                statement.executeUpdate("UPDATE music_track SET title='博主已编辑',enabled=true,sort_order=9 WHERE id='legacy-id'");
            }
            var second=run(schema.name(),report,"blog_media_test");
            assertEquals(0,second.code,second.error);assertEquals(0,second.body.path("assetsCreated").asInt());
            assertEquals(3,second.body.path("assetsPreserved").asInt());assertEquals(0,second.body.path("tracksCreated").asInt());
            try(var connection=schema.openConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT title,audio_asset_id,enabled,sort_order FROM music_track WHERE id='legacy-id'")) {
                assertTrue(rows.next());assertEquals("博主已编辑",rows.getString(1));assertEquals(assetId,rows.getString(2));
                assertTrue(rows.getBoolean(3));assertEquals(9,rows.getInt(4));
            }
        }
    }

    @Test void rollsBackNewTablesAndAssetsWhenBusinessReferenceHasWrongKind() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            var result=run(schema.name(),fixture("blog/music/a.mp3"),"blog_media_test");
            assertEquals(1,result.code);assertEquals(0,tableCount(schema));
            assertFalse(result.error.contains("blog/music"));assertFalse(result.error.contains("password"));
        }
    }

    @Test void rejectsWrongCatalogBeforeDDL() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            var result=run(schema.name(),fixture(""),"family_memory");
            assertEquals(1,result.code);assertEquals(0,tableCount(schema));
        }
    }

    @Test void changedCatalogChecksumCannotWriteData() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            Path report=fixture("");
            Files.writeString(directory.resolve("music-catalog.json"),"[]");
            var result=run(schema.name(),report,"blog_media_test");
            assertEquals(1,result.code);assertEquals(0,tableCount(schema));
        }
    }

    @Test void existingAssetTypeConflictCannotPartiallyImportBatch() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();
            schema.execute("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000001','cloudflare_imgbed','blog/music/a.mp3','IMAGE','a','ACTIVE',now(),now())");
            var result=run(schema.name(),fixture(""),"blog_media_test");
            assertEquals(1,result.code);
            try(var c=schema.openConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM media_asset")) {
                r.next();assertEquals(1,r.getInt(1));
            }
        }
    }

    @Test void importsKnownVideoCatalogWithUuidAndPreservesItsMetadata() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            Path report=fixture("");
            Path video=directory.resolve("surprise-videos.json");
            Files.writeString(video,"""
                    [{"id":"video-legacy","title":"原始视频","url":"https://example.test/a.mp4",
                      "path":"blog/surprise/a.mp4","mediaType":"video/mp4","size":10,"enabled":false,"sortOrder":3}]
                    """);
            var body=json.readTree(Files.readAllBytes(report));
            var evidence=(com.fasterxml.jackson.databind.node.ObjectNode)body.path("snapshots").get(1);
            evidence.put("available",true).put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(video))));
            Files.writeString(report,json.writeValueAsString(body));
            var result=run(schema.name(),report,"blog_media_test");
            assertEquals(0,result.code,result.error);assertEquals(1,result.body.path("videosCreated").asInt());
            try(var c=schema.openConnection();var s=c.createStatement();var r=s.executeQuery("SELECT title,video_asset_id,enabled,sort_order FROM surprise_video WHERE id='video-legacy'")) {
                assertTrue(r.next());assertEquals("原始视频",r.getString(1));assertNotNull(r.getObject(2));assertFalse(r.getBoolean(3));assertEquals(3,r.getInt(4));
            }
            assertEquals(0,run(schema.name(),report,"blog_media_test").body.path("videosCreated").asInt());
        }
    }

    @Test void refusesUnapprovedDDLWithoutCreatingAnyTables() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            Path ddl=directory.resolve("modified.sql");
            Files.writeString(ddl,Files.readString(com.blogsystem.test.RepositoryPaths.file("migration", "005_add_media_metadata_tables.sql"))+"\nCREATE TABLE not_approved(id int);\n");
            var result=run(schema.name(),fixture(""),"blog_media_test",ddl);
            assertEquals(1,result.code);assertEquals(0,tableCount(schema));
        }
    }

    @Test void duplicateInventoryBeforeFirstInsertRollsBackNewTables() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            Path report=fixture("");
            var body=json.readTree(Files.readAllBytes(report));
            var objects=(com.fasterxml.jackson.databind.node.ArrayNode)body.path("objects");
            objects.add(objects.get(0).deepCopy());
            Files.writeString(report,json.writeValueAsString(body));
            assertEquals(1,run(schema.name(),report,"blog_media_test").code);
            assertEquals(0,tableCount(schema));
        }
    }

    @Test void sameMissingPathCannotBeBothAudioAndCover() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            Path report=fixture("blog/music/a.mp3");
            var body=json.readTree(Files.readAllBytes(report));
            ((com.fasterxml.jackson.databind.node.ArrayNode)body.path("objects")).remove(0);
            Files.writeString(report,json.writeValueAsString(body));
            assertEquals(1,run(schema.name(),report,"blog_media_test").code);
            assertEquals(0,tableCount(schema));
        }
    }

    @Test void cannotImportAnotherSharedProviderNamespace() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            Path report=fixture("");
            Files.writeString(report,Files.readString(report).replace("blog", "other"));
            Path music=directory.resolve("music-catalog.json");
            Files.writeString(music,Files.readString(music).replace("blog/", "other/"));
            var body=json.readTree(Files.readAllBytes(report));
            ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("snapshots").get(0))
                    .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(music))));
            Files.writeString(report,json.writeValueAsString(body));
            assertEquals(1,run(schema.name(),report,"blog_media_test").code);
            assertEquals(0,tableCount(schema));
        }
    }

    @Test void committedButOutputFailureReturnsTwo() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            Path report=fixture("");
            var errors=new StringWriter();
            var output=new PrintWriter(new StringWriter()) {
                @Override public void println(String value) { throw new IllegalStateException("Receipt unavailable"); }
            };
            int code=MediaMetadataImportMain.execute(arguments(schema.name(),report,"blog_media_test",
                    com.blogsystem.test.RepositoryPaths.file("migration", "005_add_media_metadata_tables.sql").toAbsolutePath()),
                    environment(),output,new PrintWriter(errors));
            assertEquals(2,code);assertTrue(errors.toString().contains("COMMITTED"));
            assertEquals(3,tableCount(schema));
            try(var c=schema.openConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM music_track")) {
                assertTrue(r.next());assertEquals(1,r.getInt(1));
            }
        }
    }

    private Result run(String schema,Path report,String database) throws Exception {
        return run(schema,report,database,com.blogsystem.test.RepositoryPaths.file("migration", "005_add_media_metadata_tables.sql").toAbsolutePath());
    }

    private Result run(String schema,Path report,String database,Path ddl) throws Exception {
        var output=new StringWriter();var error=new StringWriter();
        int code=MediaMetadataImportMain.execute(arguments(schema,report,database,ddl),
                environment(),new PrintWriter(output),new PrintWriter(error));
        return new Result(code,code==0?json.readTree(output.toString()):json.createObjectNode(),error.toString());
    }

    private static Map<String,String> environment() {
        return Map.of("MEDIA_IMPORT_DB_URL",System.getenv("MEDIA_PG_TEST_URL"),
                "MEDIA_IMPORT_DB_USERNAME",System.getenv("MEDIA_PG_TEST_USERNAME"),"MEDIA_IMPORT_DB_PASSWORD",System.getenv("MEDIA_PG_TEST_PASSWORD"),"IMGBED_BASE_URL","https://img.example");
    }

    private String[] arguments(String schema,Path report,String database,Path ddl) {
        return new String[]{"--apply","--database",database,"--schema",schema,
                "--report",report.toString(),"--music-catalog",directory.resolve("music-catalog.json").toString(),
                "--surprise-catalog",directory.resolve("surprise-videos.json").toString(),
                "--playlist",directory.resolve("public-playlist.json").toString(),
                "--ddl",ddl.toString()};
    }

    private Path fixture(String cover) throws Exception {
        Path music=directory.resolve("music-catalog.json");
        Files.writeString(music,"""
                [{"id":"legacy-id","title":"原始歌曲","artist":"歌手","sourceId":"source-1","audioPath":"blog/music/a.mp3","coverPath":"%s","lyricsPath":"","enabled":false,"sortOrder":7}]
                """.formatted(cover));
        Files.writeString(directory.resolve("public-playlist.json"),"{\"songs\":[{\"id\":\"source-1\",\"cover\":\"https://example.test/cover.jpg\"}]}");
        Path report=directory.resolve("report.json");
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(music)));
        Files.writeString(report,"""
                {"mode":"READ_ONLY_DRY_RUN","provider":"cloudflare_imgbed","rootFolder":"blog","scanFinishedAt":"%s",
                 "snapshots":[{"source":"music_track","available":true,"sha256":"%s"},{"source":"surprise_video","available":false,"sha256":""}],
                 "objects":[{"kind":"AUDIO","objectKey":"blog/music/a.mp3","metadata":{"fileName":"a.mp3"}},
                 {"kind":"IMAGE","objectKey":"blog/covers/a.jpg","metadata":{"fileName":"a.jpg"}},
                 {"kind":"VIDEO","objectKey":"blog/surprise/a.mp4","metadata":{"fileName":"a.mp4"}}]}
                """.formatted(Instant.now(),sha));
        return report;
    }

    private static int tableCount(MediaPostgresTestSupport.ScopedSchema schema) throws Exception {
        try(var c=schema.openConnection();var s=c.prepareStatement("SELECT count(*) FROM pg_tables WHERE schemaname=?")) {
            s.setString(1,schema.name());try(var r=s.executeQuery()){r.next();return r.getInt(1);}
        }
    }
    private record Result(int code,com.fasterxml.jackson.databind.JsonNode body,String error) { }
}
