package com.blogsystem.it.media;

import static org.junit.jupiter.api.Assertions.*;

import com.blogsystem.test.RepositoryPaths;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;

class HotTagDeploymentMigrationTest {
    private static final String MIGRATION = "014_20261025_add_tag_is_hot_without_legacy_selection.sql";

    private void prepare(MediaPostgresTestSupport.ScopedSchema schema) throws Exception {
        schema.execute("CREATE TABLE tag(id BIGINT PRIMARY KEY,name VARCHAR(100),sort INTEGER NOT NULL)");
        schema.execute("INSERT INTO tag VALUES(7,'existing-tag',19)");
        schema.execute("CREATE TABLE unrelated(id INTEGER PRIMARY KEY)");
        schema.execute("INSERT INTO unrelated VALUES(42)");
        schema.executeDdl(RepositoryPaths.file("migration", MIGRATION));
        try (var connection = schema.openConnection();
                var columns = connection.getMetaData().getColumns(null, schema.name(), "tag", "is_hot")) {
            assertTrue(columns.next(), "Standalone upgrade must create is_hot without the retired selection table");
        }
    }

    @Test void standaloneUpgradePreservesExistingTagsAndRejectsInvalidMarkers() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            prepare(schema);
            try (var connection = schema.openConnection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT id,name,sort,is_hot FROM tag")) {
                assertTrue(rows.next()); assertEquals(7L, rows.getLong(1));
                assertEquals("existing-tag", rows.getString(2)); assertEquals(19, rows.getInt(3));
                assertEquals(0, rows.getInt(4)); assertFalse(rows.next());
            }
            assertThrows(SQLException.class, () -> schema.execute("UPDATE tag SET is_hot=2"));
            try (var connection = schema.openConnection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT id FROM unrelated")) {
                assertTrue(rows.next()); assertEquals(42, rows.getInt(1)); assertFalse(rows.next());
            }
        }
    }

    @Test void repeatedUpgradeFailsWithoutResettingAnExistingHotSelection() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            prepare(schema);
            schema.execute("UPDATE tag SET is_hot=1 WHERE id=7");
            var duplicate = assertThrows(ScriptStatementFailedException.class,
                    () -> schema.executeDdl(RepositoryPaths.file("migration", MIGRATION)));
            assertEquals("42701", assertInstanceOf(SQLException.class, duplicate.getCause()).getSQLState());
            try (var connection = schema.openConnection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT is_hot FROM tag WHERE id=7")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
        }
    }
}
