package com.blogsystem.test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only root resolution; never guesses another checkout based on the working directory. */
public final class RepositoryPaths {
    private RepositoryPaths() { }

    public static Path file(String first, String... rest) {
        String configured = System.getProperty("blog.repositoryRoot");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("The test runner must set blog.repositoryRoot");
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".git")) || !Files.isDirectory(root.resolve("blog-server"))) {
            throw new IllegalStateException("blog.repositoryRoot is not this repository");
        }
        Path resolved = root.resolve(Path.of(first, rest)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Test fixture escaped repository root");
        }
        return resolved;
    }
}
