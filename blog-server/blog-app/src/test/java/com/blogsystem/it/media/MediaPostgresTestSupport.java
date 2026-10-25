package com.blogsystem.it.media;

import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Test-only PostgreSQL guardrail for the media slice. It never falls back to
 * the regular application database and only drops a schema it generated.
 */
final class MediaPostgresTestSupport {
    private static final String EXPECTED_CATALOG = "blog_media_test";
    private static final Pattern TEST_SCHEMA = Pattern.compile("media_it_[0-9a-f]{32}");

    private MediaPostgresTestSupport() {
    }

    static ScopedSchema createSchema() throws SQLException {
        requireConfiguredEnvironment();
        String schemaName = "media_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = openRawConnection()) {
            verifyExpectedCatalog(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schemaName);
            }
        }
        return new ScopedSchema(schemaName);
    }

    private static void requireConfiguredEnvironment() {
        String url = System.getenv("MEDIA_PG_TEST_URL");
        String username = System.getenv("MEDIA_PG_TEST_USERNAME");
        String password = System.getenv("MEDIA_PG_TEST_PASSWORD");
        Assumptions.assumeTrue(isPresent(url) && isPresent(username) && isPresent(password),
                "MEDIA_PG_TEST_URL, MEDIA_PG_TEST_USERNAME and MEDIA_PG_TEST_PASSWORD are required for PostgreSQL media tests");
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static Connection openRawConnection() throws SQLException {
        return DriverManager.getConnection(System.getenv("MEDIA_PG_TEST_URL"),
                System.getenv("MEDIA_PG_TEST_USERNAME"), System.getenv("MEDIA_PG_TEST_PASSWORD"));
    }

    private static void verifyExpectedCatalog(Connection connection) throws SQLException {
        if (!EXPECTED_CATALOG.equals(connection.getCatalog())) {
            throw new IllegalStateException("MEDIA_PG_TEST_URL must target " + EXPECTED_CATALOG);
        }
    }

    static final class ScopedSchema implements AutoCloseable {
        private final String name;

        private ScopedSchema(String name) {
            if (!TEST_SCHEMA.matcher(name).matches()) {
                throw new IllegalArgumentException("Unexpected media test schema name");
            }
            this.name = name;
        }

        String name() {
            return name;
        }

        Connection openConnection() throws SQLException {
            Connection connection = openRawConnection();
            try {
                verifyExpectedCatalog(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET search_path TO " + name);
                }
                return connection;
            } catch (RuntimeException | SQLException failure) {
                connection.close();
                throw failure;
            }
        }

        void executeDdl() throws SQLException, IOException {
            executeDdl(com.blogsystem.test.RepositoryPaths.file("migration", "005_add_media_metadata_tables.sql"));
        }

        void executeDdl(Path source) throws SQLException, IOException {
            Path script = source.toAbsolutePath().normalize();
            if (!Files.isRegularFile(script)) {
                throw new IOException("Media metadata DDL script is missing: " + script);
            }
            try (Connection connection = openConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
            }
        }

        void execute(String sql) throws SQLException {
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        @Override
        public void close() throws SQLException {
            try (Connection connection = openRawConnection()) {
                verifyExpectedCatalog(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP SCHEMA " + name + " CASCADE");
                }
            }
        }
    }
}
