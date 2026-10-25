package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.infrastructure.gateway.imgbed.asset.CloudflareImageRepository;

import com.blogsystem.domain.asset.image.model.valobj.ImagePath;
import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.domain.asset.image.model.valobj.ImageUpload;
import com.blogsystem.domain.asset.image.model.valobj.StoredImage;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Assertions;

import com.blogsystem.infrastructure.configuration.asset.ImgBedSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit opt-in. Creates one small public fixture and deletes only that newly returned key.
 */
@EnabledIfEnvironmentVariable(named = "IMGBED_SMOKE", matches = "true")
class ImgBedSmokeTest {
    @Test
    void uploadReadListDeleteOneOwnedFixture() throws Exception {
        String root = System.getenv().getOrDefault("IMGBED_ROOT_FOLDER", "blog") + "/verification-" + UUID.randomUUID();
        var settings = new ImgBedSettings(System.getenv("IMGBED_BASE_URL"), System.getenv("IMGBED_API_TOKEN"),
                System.getenv().getOrDefault("IMGBED_UPLOAD_CHANNEL", "telegram"), System.getenv().getOrDefault("IMGBED_CHANNEL_NAME", "TelegramBot"), root, Duration.ofSeconds(30));
        var repository = new CloudflareImageRepository(settings, new ObjectMapper());
        byte[] fixture = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH1sAAAAASUVORK5CYII=");
        StoredImage image = repository.upload(new ImageUpload(fixture, "image/png", ImagePurpose.ARTICLE), 1);
        var path = new ImagePath(image.path());
        path.requireWithin(root + "/articles");
        try {
            var read = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(image.url())).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, read.statusCode());
            assertArrayEquals(fixture, read.body());
            var page = repository.list(0, 20, "");
            assertTrue(page.images().stream().anyMatch(row -> row.path().equals(image.path())), "Fixture must be present in provider list");
        } finally {
            // This exact object was just created by this test. No existing data/folders are deleted.
            repository.delete(path);
        }
        // Cloudflare KV/index reads may be eventually consistent. Retry reads only, never deletion.
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        boolean absent;
        do {
            absent = repository.list(0, 20, "").images().stream().noneMatch(row -> row.path().equals(image.path()));
            if (!absent) Thread.sleep(5000);
        } while (!absent && System.nanoTime() < deadline);
        assertTrue(absent, "Deleted fixture must eventually disappear from provider list");
    }
}
