package com.blogsystem.app.config.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MediaCliRuntimeWiringTest {
    @Test void missingManagedOriginRejectsBeforeAnyDatabaseConnection() {
        assertThrows(IllegalArgumentException.class,
                () -> MediaCliConfiguration.importer(environment(), "blog_media_test"));
    }

    @Test void invalidAliasRejectsBeforeAnyDatabaseConnection() {
        var environment = environment();
        environment.put("IMGBED_BASE_URL", "https://img.example");
        environment.put("MEDIA_IMPORT_IMGBED_ALLOWED_ORIGINS", "https://old.example/private-path");
        assertThrows(IllegalArgumentException.class,
                () -> MediaCliConfiguration.importer(environment, "blog_media_test"));
    }

    @Test void validOriginNeedsNeitherProviderTokenNorRunningDatabaseToAssemble() {
        var environment = environment();
        environment.put("IMGBED_BASE_URL", "https://img.example");
        environment.put("MEDIA_IMPORT_IMGBED_ALLOWED_ORIGINS", "https://old.example");
        assertNotNull(MediaCliConfiguration.importer(environment, "blog_media_test"));
    }

    private static Map<String, String> environment() {
        return new HashMap<>(Map.of("MEDIA_IMPORT_DB_URL", "jdbc:postgresql://127.0.0.1:1/blog_media_test",
                "MEDIA_IMPORT_DB_USERNAME", "isolated-test", "MEDIA_IMPORT_DB_PASSWORD", "not-a-real-secret"));
    }
}
