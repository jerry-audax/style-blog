package com.blogsystem.it.media;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MediaUploadOperationDdlIntegrationTest {
    static final Path DDL=com.blogsystem.test.RepositoryPaths.file("migration", "006_add_media_upload_operation_table.sql");
    @Test void createsEmptyLedgerWithoutChangingOldTablesAndRejectsRepeatExecution() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();schema.executeDdl(DDL);
            try(var connection=schema.openConnection();var statement=connection.createStatement();var rows=statement.executeQuery(
                    "SELECT (SELECT count(*) FROM media_asset)+(SELECT count(*) FROM music_track)+(SELECT count(*) FROM surprise_video)+(SELECT count(*) FROM media_upload_operation)")) {
                assertTrue(rows.next());assertEquals(0,rows.getLong(1));
            }
            assertThrows(RuntimeException.class,()->schema.executeDdl(DDL));
        }
    }
    @Test void databaseRejectsInvalidSlotSizeStageAndDanglingSource() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();schema.executeDdl(DDL);UUID id=UUID.randomUUID();schema.execute(insert(id));
            for(String change:new String[]{"slot='VIDEO'","file_size=104857601","file_size=0","status='SUCCEEDED',stage='PERSIST'",
                    "stage='CHUNK'","total_chunks=2","version=-1","in_flight_chunk=1","lease_owner='00000000-0000-0000-0000-000000000001'"}) {
                SQLException failure=assertThrows(SQLException.class,()->schema.execute("UPDATE media_upload_operation SET "+change+" WHERE id='"+id+"'"));
                assertEquals("23514",failure.getSQLState());
            }
            assertEquals("23503",assertThrows(SQLException.class,()->schema.execute("UPDATE media_upload_operation SET operation_type='BIND_UPLOADED',source_operation_id='00000000-0000-0000-0000-000000000404',confirmed_object_key=operation_folder||'/a.mp3',status='UPLOADED',stage='PERSIST',confirmed_chunks=1 WHERE id='"+id+"'")).getSQLState());
        }
    }
    @Test void originalFolderIsUniqueButBindingChildCanReuseIt() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();schema.executeDdl(DDL);UUID id=UUID.randomUUID();schema.execute(insert(id));
            var other=UUID.randomUUID();schema.execute(insert(other));
            assertEquals("23505",assertThrows(SQLException.class,()->schema.execute("UPDATE media_upload_operation SET operation_folder='blog/managed-uploads/"+id+"' WHERE id='"+other+"'")).getSQLState());
            schema.execute("UPDATE media_upload_operation SET operation_type='BIND_UPLOADED',source_operation_id='"+id+"',operation_folder='blog/managed-uploads/"+id+"',confirmed_object_key='blog/managed-uploads/"+id+"/a.mp3',status='UPLOADED',stage='PERSIST',confirmed_chunks=1 WHERE id='"+other+"'");
        }
    }
    @Test void rejectsMissingVideoSeedFieldsRatherThanPassingNullCheck() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();schema.executeDdl(DDL);var id=UUID.randomUUID();schema.execute(insert(id));
            assertEquals("23514",assertThrows(SQLException.class,()->schema.execute("UPDATE media_upload_operation SET target_type='SURPRISE_CREATE',slot='VIDEO',expected_target_version=NULL,mime_type='video/mp4',file_name='a.mp4',target_payload='{}' WHERE id='"+id+"'")).getSQLState());
        }
    }
    @Test void rejectsSuccessSnapshotWithoutSchemaVersion() throws Exception {
        try(var schema=MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();schema.executeDdl(DDL);var id=UUID.randomUUID();schema.execute(insert(id));
            assertEquals("23514",assertThrows(SQLException.class,()->schema.execute("UPDATE media_upload_operation SET status='SUCCEEDED',stage='PERSIST',confirmed_chunks=1,confirmed_object_key=operation_folder||'/a.mp3',result_snapshot='{\"result\":{}}' WHERE id='"+id+"'")).getSQLState());
        }
    }
    static String insert(UUID id) {
        return "INSERT INTO media_upload_operation(id,actor_id,request_fingerprint,operation_type,target_type,target_id,slot,expected_target_version,file_name,mime_type,file_size,source_sha256,candidate_asset_id,target_payload,provider,operation_folder,transport_mode,chunk_size,total_chunks,status,stage,created_at,updated_at) VALUES ('"
                +id+"',1,'"+"a".repeat(64)+"','UPLOAD_AND_BIND','MUSIC_TRACK','song','AUDIO',0,'a.mp3','audio/mpeg',1,'"+"a".repeat(64)+"','"+UUID.randomUUID()+"','{}','cloudflare_imgbed','blog/managed-uploads/"+id+"','DIRECT',16000000,1,'READY','NONE',now(),now())";
    }
}
