package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.infrastructure.gateway.imgbed.asset.CloudflareImageRepository;

import com.blogsystem.domain.asset.image.model.valobj.ImagePath;
import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.domain.asset.image.model.valobj.ImageStorageException;
import com.blogsystem.domain.asset.image.model.valobj.ImageUpload;

import com.blogsystem.domain.asset.image.model.valobj.ImageStorageException.Reason;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.blogsystem.infrastructure.configuration.asset.ImgBedSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CloudflareImageRepositoryTest {
    HttpServer server;
    CloudflareImageRepository repository;
    String response = "[]", requestPath, requestAuth, requestBody;
    int status = 200;
    AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestPath = exchange.getRequestURI().toASCIIString();
            requestAuth = exchange.getRequestHeaders().getFirst("Authorization");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (status == 302) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/leak");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        repository = new CloudflareImageRepository(settings("test-secret-not-real"), new ObjectMapper());
    }

    ImgBedSettings settings(String token) {
        return new ImgBedSettings("http://127.0.0.1:" + server.getAddress().getPort(), token, "telegram", "TelegramBot", "blog", Duration.ofSeconds(2));
    }

    @AfterEach
    void teardown() {
        server.stop(0);
    }

    @Test
    void multipartUploadUsesSameTokenCorrectChannelAndPublicUrl() {
        response = "[{\"src\":\"/file/blog/articles/a.png\"}]";
        var input = new ImageUpload(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, "image/png", ImagePurpose.ARTICLE);
        var image = repository.upload(input, 5);
        assertEquals("blog/articles/a.png", image.path());
        assertTrue(image.url().endsWith("/file/blog/articles/a.png"));
        assertEquals("Bearer test-secret-not-real", requestAuth);
        assertTrue(requestPath.contains("uploadChannel=telegram"));
        assertTrue(requestPath.contains("channelName=TelegramBot"));
        assertTrue(requestPath.contains("autoRetry=false"));
        assertTrue(requestPath.contains("uploadFolder=blog%2Farticles"));
        assertTrue(requestBody.contains("name=\"file\""));
        assertTrue(requestBody.contains("Content-Type: image/png"));
        assertFalse(requestPath.contains("test-secret"));
    }

    @Test
    void avatarFolderDerivesFromAuthenticatedIdentity() {
        response = "[{\"src\":\"/file/blog/avatars/42/a.png\"}]";
        repository.upload(new ImageUpload(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, "image/png", ImagePurpose.AVATAR), 42);
        assertTrue(requestPath.contains("uploadFolder=blog%2Favatars%2F42"));
    }

    @Test
    void listMapsAllowlistedMetadataAndBoundsNamespace() {
        response = "{\"files\":[{\"name\":\"blog/中文/a.png\",\"metadata\":{\"File-Mime\":\"image/png\",\"File-Size\":\"21\",\"private\":\"do-not-expose\"}}],\"totalCount\":1}";
        var page = repository.list(20, 20, "中文");
        assertEquals(1, page.total());
        assertEquals(21, page.images().get(0).size());
        assertTrue(page.images().get(0).url().contains("%E4%B8%AD%E6%96%87"));
        assertTrue(requestPath.contains("dir=blog"));
        assertTrue(requestPath.contains("recursive=true"));
        assertTrue(requestPath.contains("fileType=image"));
        assertEquals("Bearer test-secret-not-real", requestAuth);
        assertFalse(page.toString().contains("do-not-expose"));
    }

    @Test
    void deletionEncodesSingleKeyWithoutFolderDeletionOrRetries() {
        response = "{\"success\":true}";
        repository.delete(new ImagePath("blog/中文/a.png"));
        assertTrue(requestPath.startsWith("/api/manage/delete/blog/%E4%B8%AD%E6%96%87/a.png"));
        assertTrue(requestPath.endsWith("?folder=false"));
        assertEquals("Bearer test-secret-not-real", requestAuth);
        assertEquals(1, calls.get());
        assertThrows(IllegalArgumentException.class, () -> repository.delete(new ImagePath("other/a.png")));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsForeignUploadUrlAndMalformedSuccess() {
        response = "[{\"src\":\"https://evil.test/file/blog/a.png\"}]";
        assertThrows(ImageStorageException.class, () -> repository.upload(new ImageUpload(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, "image/png", ImagePurpose.COVER), 1));
        response = "{\"success\":false,\"error\":\"secret upstream body\"}";
        var error = assertThrows(ImageStorageException.class, () -> repository.delete(new ImagePath("blog/a.png")));
        assertFalse(error.getMessage().contains("secret"));
        assertNull(error.getCause());
    }

    @Test
    void missingTokenIsClosedAndRedirectsAreNotFollowed() {
        var disabled = new CloudflareImageRepository(settings(""), new ObjectMapper());
        assertEquals(ImageStorageException.Reason.NOT_CONFIGURED, assertThrows(ImageStorageException.class, () -> disabled.list(0, 20, "")).reason());
        assertEquals(0, calls.get());
        status = 302;
        response = "{}";
        assertThrows(ImageStorageException.class, () -> repository.list(0, 20, ""));
        assertEquals(1, calls.get());
    }

    @Test
    void oversizedResponseIsRejectedAndUpstreamErrorsAreSanitized() {
        response = "x".repeat(1024 * 1024 + 1);
        assertThrows(ImageStorageException.class, () -> repository.list(0, 20, ""));
        status = 403;
        response = "token=very-sensitive-value";
        var error = assertThrows(ImageStorageException.class, () -> repository.list(0, 20, ""));
        assertFalse(error.toString().contains("sensitive"));
    }

    @Test
    void mutationTimeoutIsUnknownAndNeverAutomaticallyRetried() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        var shortSettings = new ImgBedSettings(settings("test").baseUrl.toString(), "test", "telegram", "TelegramBot", "blog", Duration.ofMillis(100));
        var slow = new CloudflareImageRepository(shortSettings, new ObjectMapper());
        var error = assertThrows(ImageStorageException.class, () -> slow.delete(new ImagePath("blog/a.png")));
        assertEquals(ImageStorageException.Reason.UNKNOWN_OUTCOME, error.reason());
        assertEquals(1, calls.get());
    }
}
