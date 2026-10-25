package com.blogsystem.app.config.content;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Shared credential source for publisher requests and inbound machine authentication. */
public final class PublicationCredentialSupplier implements Supplier<String> {
    private final String explicitToken;
    private final boolean allowDevFile;
    private final String tokenFile;

    public PublicationCredentialSupplier(String explicitToken, boolean allowDevFile, String tokenFile) {
        this.explicitToken = explicitToken; this.allowDevFile = allowDevFile; this.tokenFile = tokenFile;
    }

    @Override public String get() {
        if (explicitToken != null && !explicitToken.isBlank()) return explicitToken.strip();
        if (!allowDevFile) return "";
        try {
            Path file = Path.of(tokenFile).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 256) return "";
            return Files.readString(file).strip();
        } catch (Exception failure) { return ""; }
    }
}
