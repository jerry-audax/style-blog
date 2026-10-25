package com.blogsystem.it.media;

import com.blogsystem.test.RepositoryPaths;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MediaDeletionDdlIntegrationTest {
    static final String MIGRATION = "011_20261017_add_media_deletion_ownership_and_operations.sql";
    static void migrate(MediaPostgresTestSupport.ScopedSchema schema) throws Exception {
        schema.executeDdl(RepositoryPaths.file("migration",MIGRATION));
    }
    static String operation(MediaPostgresTestSupport.ScopedSchema schema, long actor, String key) throws Exception {
        String id=UUID.randomUUID().toString();
        schema.execute("INSERT INTO media_delete_operation(id,actor_id,idempotency_key,fingerprint,target_type,target_id,original_version,committed_at) VALUES ('"
                +id+"',"+actor+",'"+key+"','"+"a".repeat(64)+"','MUSIC_TRACK','song',0,now())");
        return id;
    }
    static String item(MediaPostgresTestSupport.ScopedSchema schema,String operation,String asset,String status,String reason,String linked) throws Exception {
        String id=UUID.randomUUID().toString();
        schema.execute("INSERT INTO media_delete_item(id,operation_id,asset_id,media_kind,asset_version,status,reason,linked_item_id) VALUES ('"
                +id+"','"+operation+"','"+asset+"','AUDIO',0,'"+status+"','"+reason+"',"+(linked==null?"NULL":"'"+linked+"'")+")");
        return id;
    }
    @Test void defaultsProtectExistingAndNewResourcesAndRepeatedMigrationFails() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.fixture()) {
            migrate(schema);
            try(var conn=schema.openConnection();var statement=conn.createStatement();var rows=statement.executeQuery("SELECT deletion_scope,count(*) FROM media_asset GROUP BY deletion_scope")) {
                assertTrue(rows.next()); assertEquals("UNVERIFIED",rows.getString(1)); assertEquals(3,rows.getInt(2)); assertFalse(rows.next());
            }
            assertThrows(Exception.class,()->migrate(schema));
            assertThrows(SQLException.class,()->schema.execute("UPDATE media_asset SET deletion_scope='EXCLUSIVE'"));
            assertThrows(SQLException.class,()->schema.execute("UPDATE media_asset SET deletion_scope='EXCLUSIVE',ownership_confirmed_by=7,ownership_confirmed_at=now(),ownership_note='已核对',exclusive_environment=NULL"));
            assertThrows(SQLException.class,()->schema.execute("UPDATE media_asset SET deletion_scope='UNKNOWN'"));
        }
    }
    @Test void operationKeysAndResourceItemsAreUniquelyConstrained() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.fixture()) {
            migrate(schema);String key=UUID.randomUUID().toString();String op=operation(schema,7,key);
            assertThrows(SQLException.class,()->operation(schema,7,key));operation(schema,8,key);
            item(schema,op,MediaRuntimeRepositoryIntegrationTest.AUDIO,"RETAINED","OWNERSHIP_UNVERIFIED",null);
            assertThrows(SQLException.class,()->item(schema,op,MediaRuntimeRepositoryIntegrationTest.AUDIO,"RETAINED","OWNERSHIP_UNVERIFIED",null));
            assertThrows(SQLException.class,()->schema.execute("DELETE FROM media_delete_operation WHERE id='"+op+"'"));
            assertThrows(SQLException.class,()->schema.execute("DELETE FROM media_asset WHERE id='"+MediaRuntimeRepositoryIntegrationTest.AUDIO+"'"));
            assertThrows(SQLException.class,()->schema.execute("UPDATE media_delete_item SET status='RETAINED',reason='NONE'"));
            assertThrows(SQLException.class,()->schema.execute("UPDATE media_delete_item SET version=-1"));
            assertThrows(SQLException.class,()->schema.execute("UPDATE media_delete_item SET updated_at=created_at-interval '1 second'"));
        }
    }
    @Test void activeExecutorAndCanonicalLinkConstraintsCannotBeBypassed() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.fixture()) {
            migrate(schema);
            String a=operation(schema,7,UUID.randomUUID().toString()),b=operation(schema,7,UUID.randomUUID().toString()),c=operation(schema,7,UUID.randomUUID().toString());
            String active=item(schema,a,MediaRuntimeRepositoryIntegrationTest.AUDIO,"PENDING","NONE",null);
            assertThrows(SQLException.class,()->item(schema,b,MediaRuntimeRepositoryIntegrationTest.AUDIO,"PENDING","NONE",null));
            String linked=item(schema,b,MediaRuntimeRepositoryIntegrationTest.AUDIO,"LINKED","NONE",active);
            assertThrows(SQLException.class,()->item(schema,c,MediaRuntimeRepositoryIntegrationTest.VIDEO,"LINKED","NONE",active));
            assertThrows(SQLException.class,()->item(schema,c,MediaRuntimeRepositoryIntegrationTest.AUDIO,"LINKED","NONE",linked));
            assertThrows(SQLException.class,()->schema.execute("UPDATE media_delete_item SET linked_item_id=id WHERE id='"+linked+"'"));
            assertThrows(SQLException.class,()->schema.execute("UPDATE media_delete_item SET status='LINKED',linked_item_id='"+linked+"' WHERE id='"+active+"'"));
        }
    }
}
