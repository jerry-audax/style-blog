package com.blogsystem.app.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaMetadataImportMainTest {
    @Test void invalidArgumentsDoNotOpenDatabaseOrStartNormalApplication() {
        try (var connections = mockStatic(DriverManager.class)) {
            var errors = new StringWriter();
            assertEquals(1, MediaMetadataImportMain.execute(new String[0], Map.of(),
                    new PrintWriter(new StringWriter()), new PrintWriter(errors)));
            assertTrue(errors.toString().contains("stage=INPUT"));
            connections.verifyNoInteractions();
        }
    }
}
