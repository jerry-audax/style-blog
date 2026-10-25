package com.blogsystem.app.config.content;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicationCredentialSupplierTest {
    @TempDir Path directory;

    @Test void explicitCredentialWinsWithoutOpeningTheConfiguredFile() {
        assertEquals("fixture-key", new PublicationCredentialSupplier(" fixture-key ", true, "\u0000invalid-file").get());
    }

    @Test void productionDoesNotReadDevCredentialFiles() throws Exception {
        var file = directory.resolve("publication.key"); Files.writeString(file, "synthetic-test-credential");
        assertEquals("", new PublicationCredentialSupplier("", false, file.toString()).get());
        assertEquals("synthetic-test-credential", new PublicationCredentialSupplier("", true, file.toString()).get());
    }

    @Test void missingOversizedAndDirectoryPathsCannotSupplyACredential() throws Exception {
        assertEquals("", new PublicationCredentialSupplier("", true, directory.resolve("absent").toString()).get());
        assertEquals("", new PublicationCredentialSupplier("", true, directory.toString()).get());
        var file = directory.resolve("oversized.key"); Files.writeString(file, "x".repeat(257));
        assertEquals("", new PublicationCredentialSupplier("", true, file.toString()).get());
    }
}
