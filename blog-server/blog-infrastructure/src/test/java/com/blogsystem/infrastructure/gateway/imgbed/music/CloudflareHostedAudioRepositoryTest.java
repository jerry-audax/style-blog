package com.blogsystem.infrastructure.gateway.imgbed.music;

import com.blogsystem.infrastructure.gateway.imgbed.music.CloudflareHostedAudioRepository;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.blogsystem.infrastructure.configuration.asset.ImgBedSettings;
import com.blogsystem.domain.music.model.HostedMusicUnavailable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CloudflareHostedAudioRepositoryTest {
    HttpServer server;
    ObjectMapper json = new ObjectMapper();
    MutableClock clock = new MutableClock();
    CloudflareHostedAudioRepository repository;
    AtomicInteger calls = new AtomicInteger();
    List<String> routes = new ArrayList<>();
    String auth, method;
    int status = 200;
    String response = "{\"files\":[],\"totalCount\":0}";
    Function<String, String> responder = route -> response;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            String route = exchange.getRequestURI().toASCIIString();
            routes.add(route);
            method = exchange.getRequestMethod();
            auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (status == 302) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/secret");
            byte[] body = responder.apply(route).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        repository = new CloudflareHostedAudioRepository(settings("test-not-a-real-token"), json, clock);
    }

    ImgBedSettings settings(String token) {
        return new ImgBedSettings("http://127.0.0.1:" + server.getAddress().getPort(), token,
                "telegram", "TelegramBot", "blog", Duration.ofSeconds(2));
    }

    Map<String, Object> audio(String path, String name, String mime) {
        return Map.of("name", path, "metadata", Map.of("FileName", name, "FileType", mime,
                "FileSizeBytes", 321, "TgFileId", "never-export", "UploadIP", "private-ip"));
    }

    String page(List<?> files, int total) {
        try {
            return json.writeValueAsString(Map.of("files", files, "totalCount", total));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void requestsOnlyAudioNamespaceAndProjectsSafePublicFields() {
        response = page(List.of(audio("blog/music/1_翼をください.m4a", "翼をください.m4a", "audio/x-m4a"),
                audio("blog/music/2_Lover.mp3", "Lover.mp3", "audio/mpeg")), 2);
        var songs = repository.findPublished();
        assertEquals(2, songs.size());
        assertEquals("Lover", songs.get(0).name());
        assertEquals("翼をください", songs.get(1).name());
        assertTrue(songs.get(1).url().contains("%E7%BF%BC"));
        assertEquals(321, songs.get(1).size());
        assertEquals("GET", method);
        assertEquals("Bearer test-not-a-real-token", auth);
        assertTrue(routes.get(0).startsWith("/api/manage/list?dir=blog%2Fmusic&recursive=false&fileType=audio&accessStatus=normal"));
        assertFalse(songs.toString().contains("never-export"));
        assertFalse(songs.toString().contains("private-ip"));
        assertFalse(songs.toString().contains("token"));
        assertFalse(routes.get(0).contains("token"));
    }

    @Test
    void excludesOtherFoldersSubdirectoriesImagesVideosAndUnsafePaths() {
        var files = List.of(audio("blog/music/direct.mp4", "audio.mp4", "audio/mp4"),
                audio("other/music/private.mp3", "private.mp3", "audio/mpeg"),
                audio("blog/music/audio/probe/test.mp4", "probe.mp4", "audio/mp4"),
                audio("blog/music/image.png", "image.png", "image/png"),
                audio("blog/music/video.mp4", "video.mp4", "video/mp4"),
                audio("blog/music/../private.mp3", "private.mp3", "audio/mpeg"),
                audio("blog/music/%2e%2e.mp3", "private.mp3", "audio/mpeg"),
                audio("blog/music/query?token=x.mp3", "private.mp3", "audio/mpeg"),
                audio("blog/music/line\n.mp3", "private.mp3", "audio/mpeg"));
        response = page(files, files.size());
        var songs = repository.findPublished();
        assertEquals(1, songs.size());
        assertEquals("audio", songs.get(0).name());
        assertEquals("audio/mp4", songs.get(0).mediaType());
    }

    @Test
    void appliesBlacklistAgainEvenIfProviderIgnoresFilter() {
        var blocked = Map.of("name", "blog/music/blocked.mp3", "metadata", Map.of("FileType", "audio/mpeg", "ListType", "Block"));
        var adult = Map.of("name", "blog/music/adult.mp3", "metadata", Map.of("FileType", "audio/mpeg", "Label", "adult"));
        response = page(List.of(blocked, adult), 2);
        assertTrue(repository.findPublished().isEmpty());
    }

    @Test
    void paginationDoesNotSilentlyTruncateAtTheFirstHundred() {
        var first = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) first.add(audio("blog/music/" + i + ".mp3", i + ".mp3", "audio/mpeg"));
        responder = route -> route.contains("start=0&") ? page(first, 101)
                : page(List.of(audio("blog/music/100.mp3", "100.mp3", "audio/mpeg")), 101);
        assertEquals(101, repository.findPublished().size());
        assertEquals(2, calls.get());
        assertTrue(routes.get(1).contains("start=100&count=100"));
    }

    @Test
    void cachesSuccessfulReadsAndDoesNotServeStaleDataAfterFailedRefresh() {
        response = page(List.of(audio("blog/music/a.mp3", "a.mp3", "audio/mpeg")), 1);
        assertEquals(1, repository.findPublished().size());
        repository.findPublished();
        assertEquals(1, calls.get());
        clock.advance(61);
        status = 503;
        assertThrows(HostedMusicUnavailable.class, repository::findPublished);
        assertThrows(HostedMusicUnavailable.class, repository::findPublished);
        assertEquals(2, calls.get());
        clock.advance(6);
        status = 200;
        response = page(List.of(), 0);
        assertTrue(repository.findPublished().isEmpty());
        assertEquals(3, calls.get());
    }

    @Test
    void rejectsPartialDuplicateOversizedAndMalformedListsWithoutLeakingDiagnostics() {
        var a = audio("blog/music/a.mp3", "a.mp3", "audio/mpeg");
        for (String invalid : List.of("upstream-secret", "{}", "[]", page(List.of(), 1), page(List.of(a, a), 2),
                page(List.of(a), 0), page(List.of(), 2001), "{\"files\":[],\"totalCount\":\"0\"}", "x".repeat(1024 * 1024 + 1))) {
            response = invalid;
            clock.advance(6);
            var error = assertThrows(HostedMusicUnavailable.class, repository::findPublished);
            assertEquals("本站音频歌单暂时不可用，请稍后重试", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void refusesMissingCredentialsAndNeverFollowsProviderRedirects() {
        var missing = new CloudflareHostedAudioRepository(settings(""), json, clock);
        assertThrows(HostedMusicUnavailable.class, missing::findPublished);
        assertEquals(0, calls.get());
        status = 302;
        response = "credential=secret";
        assertThrows(HostedMusicUnavailable.class, repository::findPublished);
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsChangedTotalOrEmptyLaterPagesRatherThanPublishingPartialPlaylist() {
        var first = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) first.add(audio("blog/music/" + i + ".mp3", i + ".mp3", "audio/mpeg"));
        for (int laterTotal : List.of(101, 100)) {
            responder = route -> route.contains("start=0&") ? page(first, 101) : page(List.of(), laterTotal);
            clock.advance(6);
            assertThrows(HostedMusicUnavailable.class, repository::findPublished);
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }
}
