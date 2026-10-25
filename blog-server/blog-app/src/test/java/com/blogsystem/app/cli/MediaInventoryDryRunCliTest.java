package com.blogsystem.app.cli;

import com.blogsystem.app.cli.MediaInventoryDryRunMain;

import java.util.Arrays;
import org.junit.jupiter.api.Assertions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MediaInventoryDryRunCliTest {
    @Test
    void cliWorksFromChineseAndSpacePaths() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("中文 空格"));
        Path music = Files.move(music("blog/music/a.mp3"), folder.resolve("音 乐.json"));
        Path videos = folder.resolve("惊喜 视频.json");
        Files.writeString(videos, "[]");
        var requests = new AtomicInteger();
        HttpServer server = server(requests, false);
        try {
            var output = new StringWriter();
            int code = MediaInventoryDryRunMain.execute(args(music, videos), environment(server),
                    new PrintWriter(output), new PrintWriter(new StringWriter()));
            assertEquals(0, code);
            assertEquals(3, requests.get());
            assertEquals(music.toAbsolutePath().normalize().toString(),
                    json.readTree(output.toString()).path("snapshots").get(0).path("path").asText());
        } finally { server.stop(0); }
    }
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void outputsScopedMetadataAndDifferencesWithoutChangingSnapshots() throws Exception {
        Path music = music("blog/music/a.mp3");
        Path videos = directory.resolve("surprise-videos.json");
        Files.writeString(videos, "[]");
        byte[] original = Files.readAllBytes(music);
        var requests = new AtomicInteger();
        HttpServer server = server(requests, false);
        try {
            var out = new StringWriter();
            var err = new StringWriter();
            int code = MediaInventoryDryRunMain.execute(
                    args(music, videos), environment(server), new PrintWriter(out), new PrintWriter(err));
            var report = json.readTree(out.toString());
            assertEquals(0, code, err.toString());
            assertEquals("cloudflare_imgbed", report.path("provider").asText());
            assertEquals(1, report.path("summary").path("MATCHED").asInt());
            assertEquals(1, report.path("inventoryCounts").path("AUDIO").asInt());
            assertFalse(report.path("productionImportApproved").asBoolean());
            assertTrue(report.path("catalogSnapshotsComplete").asBoolean());
            assertEquals(64, report.path("snapshots").get(0).path("sha256").asText().length());
            assertEquals(42, report.path("objects").get(0).path("metadata").path("fileSize").asInt());
            assertFalse(out.toString().contains("fixture-token"));
            assertArrayEquals(original, Files.readAllBytes(music));
            assertEquals("[]", Files.readString(videos));
            assertEquals(3, requests.get());
        } finally { server.stop(0); }
    }

    @Test
    void refusesMissingSnapshotBeforeContactingProvider() throws Exception {
        HttpServer server = server(new AtomicInteger(), false);
        try {
            var out = new StringWriter();
            var err = new StringWriter();
            int code = MediaInventoryDryRunMain.execute(args(
                    music("blog/music/a.mp3"), directory.resolve("missing.json")),
                    environment(server), new PrintWriter(out), new PrintWriter(err));
            assertEquals(1, code);
            assertEquals("", out.toString());
            assertFalse(err.toString().contains("fixture-token"));
        } finally { server.stop(0); }
    }

    @Test
    void explicitlyAllowedAbsentVideoSnapshotProducesIncompleteReport() throws Exception {
        HttpServer server = server(new AtomicInteger(), false);
        try {
            var out = new StringWriter();
            var err = new StringWriter();
            String[] normal = args(music("blog/music/a.mp3"), directory.resolve("missing.json"));
            String[] options = java.util.Arrays.copyOf(normal, normal.length + 1);
            options[normal.length] = "--allow-missing-surprise-snapshot";
            int code = MediaInventoryDryRunMain.execute(options, environment(server),
                    new PrintWriter(out), new PrintWriter(err));
            var report = json.readTree(out.toString());
            assertEquals(2, code);
            assertFalse(report.path("catalogSnapshotsComplete").asBoolean());
            assertTrue(report.path("warnings").toString().contains("SURPRISE_SNAPSHOT_MISSING"));
        } finally { server.stop(0); }
    }

    @Test
    void rejectsInvalidNamespaceAndDuplicateBusinessIdsBeforeNetwork() throws Exception {
        var env = Map.of("IMGBED_BASE_URL", "http://127.0.0.1:1", "IMGBED_API_TOKEN", "fixture-token");
        Path videos = directory.resolve("surprise-videos.json");
        Files.writeString(videos, "[]");
        var out = new StringWriter();
        int code = MediaInventoryDryRunMain.execute(args(music("other/music/a.mp3"), videos),
                env, new PrintWriter(out), new PrintWriter(new StringWriter()));
        assertEquals(1, code);
        assertEquals("", out.toString());
        Path music = music("blog/music/a.mp3");
        String track = Files.readString(music);
        Files.writeString(music, "[" + track.substring(1, track.length() - 1) + ","
                + track.substring(1, track.length() - 1) + "]");
        assertEquals(1, MediaInventoryDryRunMain.execute(args(music, videos), env,
                new PrintWriter(out), new PrintWriter(new StringWriter())));
    }

    @Test
    void providerFailureDoesNotPrintTokenOrVendorResponse() throws Exception {
        HttpServer server = server(new AtomicInteger(), true);
        try {
            Path videos = directory.resolve("surprise-videos.json");
            Files.writeString(videos, "[]");
            var out = new StringWriter();
            var err = new StringWriter();
            int code = MediaInventoryDryRunMain.execute(args(music("blog/music/a.mp3"), videos),
                    environment(server), new PrintWriter(out), new PrintWriter(err));
            assertEquals(1, code);
            assertEquals("", out.toString());
            assertFalse(err.toString().contains("fixture-token"));
            assertFalse(err.toString().contains("vendor-private-detail"));
        } finally { server.stop(0); }
    }

    @Test
    void returnsFailureWhenReportOutputCannotBeWritten() throws Exception {
        HttpServer server = server(new AtomicInteger(), false);
        try {
            Path videos = directory.resolve("surprise-videos.json");
            Files.writeString(videos, "[]");
            Writer broken = new Writer() {
                @Override public void write(char[] text, int offset, int length) throws IOException { throw new IOException(); }
                @Override public void flush() { }
                @Override public void close() { }
            };
            assertEquals(1, MediaInventoryDryRunMain.execute(args(music("blog/music/a.mp3"), videos),
                    environment(server), new PrintWriter(broken), new PrintWriter(new StringWriter())));
        } finally { server.stop(0); }
    }

    @Test
    void refusesInconsistentProviderMimeRatherThanMisreportingMissing() throws Exception {
        HttpServer server = server(new AtomicInteger(), false, "video/mp4");
        try {
            Path videos = directory.resolve("surprise-videos.json");
            Files.writeString(videos, "[]");
            var out = new StringWriter();
            assertEquals(1, MediaInventoryDryRunMain.execute(args(music("blog/music/a.mp3"), videos),
                    environment(server), new PrintWriter(out), new PrintWriter(new StringWriter())));
            assertEquals("", out.toString());
        } finally { server.stop(0); }
    }

    private Path music(String path) throws Exception {
        Path music = directory.resolve("music-catalog.json");
        Files.writeString(music, """
                [{"id":"track-1","title":"示例歌曲","artist":"歌手","sourceId":"",
                  "audioPath":"%s","coverPath":"","lyricsPath":"","enabled":true,"sortOrder":0}]
                """.formatted(path).trim());
        return music;
    }

    private static String[] args(Path music, Path surprise) {
        return new String[]{"--dry-run", "--music-catalog", music.toString(),
                "--surprise-catalog", surprise.toString()};
    }

    private static Map<String, String> environment(HttpServer server) {
        return Map.of("IMGBED_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort(),
                "IMGBED_API_TOKEN", "fixture-token");
    }

    private static HttpServer server(AtomicInteger requests, boolean fail) throws Exception {
        return server(requests, fail, "audio/mpeg");
    }

    private static HttpServer server(AtomicInteger requests, boolean fail, String mime) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/manage/list", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) throw new AssertionError("dry-run must be read-only");
            if (!"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization")))
                throw new AssertionError("expected Authorization header");
            requests.incrementAndGet();
            String body = fail ? "vendor-private-detail fixture-token" :
                    exchange.getRequestURI().getQuery().contains("fileType=audio") ? """
                    {"totalCount":1,"files":[{"name":"blog/music/a.mp3",
                    "metadata":{"File-Mime":"%s","FileSizeBytes":42}}]}
                    """.formatted(mime) : "{\"totalCount\":0,\"files\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 401 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }
}
