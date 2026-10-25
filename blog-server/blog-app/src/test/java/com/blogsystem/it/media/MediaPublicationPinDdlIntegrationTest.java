package com.blogsystem.it.media;

import com.blogsystem.test.RepositoryPaths;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaPublicationPinDdlIntegrationTest {
    static final String MIGRATION = "012_20261018_add_publication_media_pins.sql";
    static void migrate(MediaPostgresTestSupport.ScopedSchema schema) throws Exception {
        schema.executeDdl(RepositoryPaths.file("migration", MIGRATION));
    }
    static void insert(MediaPostgresTestSupport.ScopedSchema schema) throws Exception {
        schema.execute("INSERT INTO media_publication_pin(environment_id,publisher_id,release_id,manifest_digest,asset_ids,status,created_at,updated_at) "
                + "VALUES ('local','hexo','Release-1','" + "a".repeat(64) + "','{}','HELD',now(),now())");
    }

    @Test void emptyManifestHasDurableRootVersionDefaultAndPartialGinIndex() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            migrate(schema); insert(schema);
            try (var connection = schema.openConnection(); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT version,cardinality(asset_ids),status FROM media_publication_pin")) {
                    assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); assertEquals(0, rows.getInt(2)); assertEquals("HELD", rows.getString(3));
                }
                try (var rows = statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema() AND indexname='ix_media_publication_pin_held_assets'")) {
                    assertTrue(rows.next()); String index = rows.getString(1);
                    assertTrue(index.contains("USING gin (asset_ids)")); assertTrue(index.contains("WHERE") && index.contains("HELD"));
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='media_publication_pin'")) {
                    assertTrue(rows.next()); assertEquals(10, rows.getInt(1));
                }
            }
            assertThrows(Exception.class, () -> migrate(schema));
        }
    }

    @Test void databaseRejectsInvalidIdentityStateTimeAndArrayShapes() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            migrate(schema); insert(schema);
            for (String change : new String[]{"environment_id='Local'", "publisher_id='bad/name'", "release_id='v_1'",
                    "manifest_digest='" + "A".repeat(64) + "'", "status='EXPIRED'", "version=-1", "asset_ids=NULL",
                    "updated_at=created_at-interval '1 second'", "released_at=now()", "status='RELEASED'",
                    "asset_ids=ARRAY[NULL]::uuid[]", "asset_ids=array_fill('00000000-0000-0000-0000-000000000001'::uuid,ARRAY[10001])",
                    "asset_ids='[0:0]={00000000-0000-0000-0000-000000000001}'::uuid[]",
                    "asset_ids=ARRAY[ARRAY['00000000-0000-0000-0000-000000000001'::uuid]]"}) {
                assertThrows(SQLException.class, () -> schema.execute("UPDATE media_publication_pin SET " + change), change);
            }
            schema.execute("UPDATE media_publication_pin SET asset_ids=array_fill('00000000-0000-0000-0000-000000000001'::uuid,ARRAY[10000])");
            schema.execute("UPDATE media_publication_pin SET status='RELEASED',version=1,released_at=updated_at");
        }
    }
}
