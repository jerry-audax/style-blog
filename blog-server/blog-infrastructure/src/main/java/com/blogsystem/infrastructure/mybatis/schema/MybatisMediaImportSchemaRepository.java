package com.blogsystem.infrastructure.mybatis.schema;

import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure;
import com.blogsystem.domain.asset.inventory.repository.MediaImportSchemaRepository;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.Set;

import javax.sql.DataSource;

/** Explicit offline DDL preparation; never registered in normal application startup. */
public final class MybatisMediaImportSchemaRepository implements MediaImportSchemaRepository {
    private static final String APPROVED_DDL_SHA256 =
            "890ecc8ea45552eeceabff2cd6838c1c0a4fa45e3d05a7f3a2cc3c1de07eaf3d";
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public MybatisMediaImportSchemaRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public boolean prepare(String database, String schema, Path ddl) {
        try {
            return Boolean.TRUE.equals(
                    jdbc.execute(
                            (ConnectionCallback<Boolean>)
                                    connection -> prepare(connection, database, schema, ddl)));
        } catch (MediaImportFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw safe("DATABASE_GUARD", failure);
        }
    }

    private boolean prepare(Connection connection, String database, String schema, Path ddl) {
        String stage = "DATABASE_GUARD";
        try {
            if (!Set.of("blog_system", "blog_media_test").contains(database)
                    || !("public".equals(schema)
                            || (database.equals("blog_media_test")
                                    && schema != null
                                    && schema.matches("media_it_[0-9a-f]{32}")))
                    || !database.equals(connection.getCatalog())
                    // JdbcTemplate supplies a close-suppressing ConnectionProxy, not the holder's
                    // target identity.
                    || !DataSourceUtils.isConnectionTransactional(
                            DataSourceUtils.getTargetConnection(connection), dataSource))
                throw new IllegalArgumentException();
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path TO " + schema);
                statement.execute("SET LOCAL lock_timeout TO '5s'");
                statement.execute("SET LOCAL statement_timeout TO '30s'");
                statement.execute("SELECT pg_advisory_xact_lock(239851013)");
            }
            stage = "DDL";
            int tableCount;
            try (var statement =
                    connection.prepareStatement(
                            "SELECT count(*) FROM pg_tables WHERE schemaname=? AND tablename IN"
                                    + " ('media_asset','music_track','surprise_video')")) {
                statement.setString(1, schema);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    tableCount = rows.getInt(1);
                }
            }
            if (tableCount != 0 && tableCount != 3)
                throw new IllegalStateException("Partial media schema");
            if (tableCount == 3) return false;
            stage = "DDL_CHECK";
            byte[] reviewed =
                    Files.readString(ddl, StandardCharsets.UTF_8)
                            .replace("\r\n", "\n")
                            .getBytes(StandardCharsets.UTF_8);
            String checksum =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reviewed));
            if (!APPROVED_DDL_SHA256.equals(checksum))
                throw new IllegalArgumentException("Migration differs from approved DDL");
            stage = "DDL_APPLY";
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new ByteArrayResource(reviewed), StandardCharsets.UTF_8));
            return true;
        } catch (Exception failure) {
            throw safe(stage, failure);
        }
    }

    private static MediaImportFailure safe(String stage, Exception failure) {
        return new MediaImportFailure(
                stage,
                MediaImportFailure.Outcome.ROLLBACK_ATTEMPTED,
                failure.getClass().getSimpleName());
    }
}
