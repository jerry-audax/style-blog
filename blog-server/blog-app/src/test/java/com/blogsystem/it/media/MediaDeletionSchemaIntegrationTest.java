package com.blogsystem.it.media;

import com.blogsystem.infrastructure.mybatis.schema.MediaDeletionSchemaVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class MediaDeletionSchemaIntegrationTest {
    @Test void sameNamedWeakConstraintsCannotEnableDeletion() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            var verifier=new MediaDeletionSchemaVerifier(f.dataSource);assertTrue(verifier.available());
            f.jdbc.execute("ALTER TABLE media_delete_operation DROP CONSTRAINT uk_media_delete_actor_key");
            f.jdbc.execute("ALTER TABLE media_delete_operation ADD CONSTRAINT uk_media_delete_actor_key CHECK(true)");
            assertFalse(verifier.available(),"same name is not proof of idempotency uniqueness");
        }
    }
    @Test void wrongPartialIndexAndUnsafeOwnershipDefaultCannotPassReadiness() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture()){
            var verifier=new MediaDeletionSchemaVerifier(f.dataSource);
            f.jdbc.execute("DROP INDEX uk_media_delete_active_asset");
            f.jdbc.execute("CREATE UNIQUE INDEX uk_media_delete_active_asset ON media_delete_item(asset_id) WHERE status='SUCCEEDED'");
            assertFalse(verifier.available());
        }
        try(var f=new MediaDeletionWorkflowFixture()){
            f.jdbc.execute("ALTER TABLE media_asset ALTER COLUMN deletion_scope DROP NOT NULL");
            assertFalse(new MediaDeletionSchemaVerifier(f.dataSource).available());
        }
    }
}
