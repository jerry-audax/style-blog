package com.blogsystem.infrastructure.gateway.netease;

import com.blogsystem.infrastructure.gateway.netease.NeteaseSongArtworkRepository;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient.Redirect;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NeteaseSongArtworkRepositoryTest {
    private HttpServer server;
    private NeteaseSongArtworkRepository repository;
    private final AtomicInteger calls = new AtomicInteger();
    private String body;
    private int status = 200;
    private String query;
    private String authorization;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/detail", exchange -> {
            calls.incrementAndGet();
            query = exchange.getRequestURI().getRawQuery();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        repository = new NeteaseSongArtworkRepository(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/detail"), Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void batchesRequestedIdsWithoutAuthenticationAndCachesTheResult() {
        body = "{\"code\":200,\"songs\":[{\"id\":1,\"album\":{\"picUrl\":\"https://p1.music.126.net/a.jpg\"}},{\"id\":9,\"album\":{\"picUrl\":\"https://p1.music.126.net/b.jpg\"}}]}";
        assertEquals(Map.of("1", "https://p1.music.126.net/a.jpg"), repository.findCovers(Set.of("1", "2")));
        assertEquals(Map.of("1", "https://p1.music.126.net/a.jpg"), repository.findCovers(Set.of("1", "2")));
        assertEquals(1, calls.get());
        assertEquals("ids=[\"1\",\"2\"]", URLDecoder.decode(query, StandardCharsets.UTF_8));
        assertNull(authorization);
    }

    @Test
    void malformedOrUnavailableArtworkIsOptionalWithBoundedBackoff() {
        body = "not-json";
        assertTrue(repository.findCovers(Set.of("1")).isEmpty());
        assertTrue(repository.findCovers(Set.of("1")).isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void errorStatusNeverReturnsProviderContent() {
        status = 503;
        body = "token=fixture-private";
        assertTrue(repository.findCovers(Set.of("1")).isEmpty());
    }

    @Test
    void oversizedResponseIsRejectedDuringStreaming() {
        body = "x".repeat(1_048_577);
        assertTrue(repository.findCovers(Set.of("1")).isEmpty());
    }

    @Test
    void nonnumericIdsNeverTriggerARequest() {
        assertTrue(repository.findCovers(Set.of("abc", "1&token=secret", "-1")).isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void acceptsOnlyPublicHttpsNeteaseArtworkWithoutCredentials() {
        assertEquals("https://p1.music.126.net/a.jpg", NeteaseSongArtworkRepository.safeCover("https://p1.music.126.net/a.jpg"));
        for (var value : List.of("http://p1.music.126.net/a.jpg", "https://p1.music.126.net.evil.test/a.jpg", "https://music.126.net.evil/a.jpg",
                "https://secret@p1.music.126.net/a.jpg", "https://p1.music.126.net/a.jpg?token=secret", "javascript:alert(1)"))
            assertEquals("", NeteaseSongArtworkRepository.safeCover(value));
    }

    @Test
    void springCanSelectTheProductionConstructorWithoutExternalRequests() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(NeteaseSongArtworkRepository.class);
            context.refresh();
            assertNotNull(context.getBean(NeteaseSongArtworkRepository.class));
        }
    }
}
