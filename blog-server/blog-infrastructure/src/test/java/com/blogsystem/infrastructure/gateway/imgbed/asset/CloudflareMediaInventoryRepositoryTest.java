package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.infrastructure.gateway.imgbed.asset.CloudflareMediaInventoryRepository;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.infrastructure.configuration.asset.ImgBedSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudflareMediaInventoryRepositoryTest {
    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/manage/list", this::respond);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsAllPagesAndDropsUnsafeOrWrongTypeEntries() {
        CloudflareMediaInventoryRepository repository = repository();

        List<MediaInventoryObject> objects = repository.list(MediaKind.IMAGE);

        assertEquals(List.of("blog/images/a.png", "blog/images/b.webp"),
                objects.stream().map(item -> item.objectKey().value()).toList());
        assertEquals(123L, objects.get(0).metadata().fileSize());
        assertTrue(requests.stream().allMatch(request -> request.contains("fileType=image")));
        assertTrue(requests.stream().allMatch(request -> request.contains("Authorization: Bearer test-token")));
    }

    @Test
    void rejectsDuplicateObjectKeysAcrossPages() {
        server.removeContext("/api/manage/list");
        server.createContext("/api/manage/list", exchange -> {
            requests.add(requestSummary(exchange));
            write(exchange, """
                    {"files":[
                      {"name":"blog/audio/song.mp3","metadata":{"File-Mime":"audio/mpeg","FileSizeBytes":1}},
                      {"name":"blog/audio/song.mp3","metadata":{"File-Mime":"audio/mpeg","FileSizeBytes":1}}
                    ],"totalCount":2}
                    """);
        });

        assertThrows(MediaInventoryException.class, () -> repository().list(MediaKind.AUDIO));
    }

    @Test
    void rejectsMalformedPageInsteadOfReturningPartialInventory() {
        server.removeContext("/api/manage/list");
        server.createContext("/api/manage/list", exchange -> {
            requests.add(requestSummary(exchange));
            write(exchange, "{\"files\":[],\"totalCount\":3}");
        });

        assertThrows(MediaInventoryException.class, () -> repository().list(MediaKind.VIDEO));
    }

    private CloudflareMediaInventoryRepository repository() {
        return new CloudflareMediaInventoryRepository(
                new ImgBedSettings("http://127.0.0.1:" + server.getAddress().getPort(),
                        "test-token", "telegram", "TelegramBot", "blog", Duration.ofSeconds(2)),
                new ObjectMapper());
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(requestSummary(exchange));
        String query = exchange.getRequestURI().getRawQuery();
        int start = query != null && query.contains("start=4") ? 4 : 0;
        if (start == 0) {
            write(exchange, """
                    {"files":[
                      {"name":"blog/images/a.png","metadata":{"File-Mime":"image/png","FileSizeBytes":123}},
                      {"name":"blog/images/../secret.png","metadata":{"File-Mime":"image/png","FileSizeBytes":9}},
                      {"name":"blog/images/not-audio.mp3","metadata":{"File-Mime":"audio/mpeg","FileSizeBytes":8}},
                      {"name":"blog/images/b.webp","metadata":{"File-Mime":"image/webp","FileSizeBytes":0}}
                    ],"totalCount":6}
                    """);
        } else {
            write(exchange, """
                    {"files":[
                      {"name":"outside/images/c.png","metadata":{"File-Mime":"image/png","FileSizeBytes":3}},
                      {"name":"blog/images/c.txt","metadata":{"File-Mime":"text/plain","FileSizeBytes":4}}
                    ],"totalCount":6}
                    """);
        }
    }

    private static String requestSummary(HttpExchange exchange) {
        return exchange.getRequestURI() + " Authorization: " + exchange.getRequestHeaders().getFirst("Authorization");
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
