package com.blogsystem.it.media;

import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;

import com.blogsystem.trigger.http.asset.MediaUploadController;
import com.blogsystem.trigger.http.asset.MediaUploadExceptionHandler;

import com.blogsystem.application.asset.upload.assembler.MediaUploadViewProjector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import com.blogsystem.infrastructure.mybatis.schema.MediaUploadSchemaVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaUploadApiIntegrationTest {
    @Test void invalidResultProjectionCannotCommitBusinessChanges() throws Exception {
        try(var f=new MediaUploadWorkflowFixture();var auth=new CatalogMvcTestSupport()) {
            f.urls=(provider,key)->{throw new IllegalStateException("Bad URL configuration");};
            var mvc=auth.build(new Object[]{new MediaUploadController(f.service(),new MediaUploadViewProjector(f.urls),auth.identity())},new MediaUploadExceptionHandler());
            var request=MediaUploadWorkflowFixture.request(1,com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot.AUDIO,7);f.service().create(1,request);
            mvc.perform(multipart(BASE+"/"+request.operationId()+"/content").file(new MockMultipartFile("file","song.mp3","audio/mpeg",new byte[]{0})).with(r->{r.setMethod("PUT");return r;}).header("Authorization",auth.token(1))).andExpect(status().isServiceUnavailable());
            assertEquals(7,f.music.findSnapshotById(request.targetId()).orElseThrow().version());
            var op=f.service().get(1,request.operationId());assertEquals(com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus.UPLOADED,op.progress().status());assertTrue(f.assets.findById(op.intent().candidateAssetId()).isEmpty());assertEquals(List.of("direct"),f.storage.calls);
        }
    }
    static final String BASE="/api/admin/media/uploads";
    @Test void missingOrDamagedSchemaFailsWithoutWrites() throws Exception {
        try(var f=new MediaUploadWorkflowFixture()) {
            var verifier=new MediaUploadSchemaVerifier(f.dataSource);var jdbc=new JdbcTemplate(f.dataSource);
            String before=fingerprint(jdbc);verifier.verify();assertEquals(before,fingerprint(jdbc));
            f.execute("ALTER TABLE media_upload_operation DROP CONSTRAINT ck_media_upload_actor; ALTER TABLE media_upload_operation ADD CONSTRAINT ck_media_upload_actor CHECK (TRUE)");
            String damaged=fingerprint(jdbc);assertThrows(IllegalStateException.class,verifier::verify);assertEquals(damaged,fingerprint(jdbc));
            f.execute("ALTER TABLE media_upload_operation RENAME TO deliberately_unavailable_uploads");assertThrows(IllegalStateException.class,verifier::verify);
        }
        try(var f=new MediaUploadWorkflowFixture()) {f.execute("ALTER TABLE media_upload_operation ALTER COLUMN provider TYPE varchar(33)");assertThrows(IllegalStateException.class,()->new MediaUploadSchemaVerifier(f.dataSource).verify());}
        try(var f=new MediaUploadWorkflowFixture()) {f.execute("ALTER TABLE media_upload_operation ALTER COLUMN actor_id DROP NOT NULL");assertThrows(IllegalStateException.class,()->new MediaUploadSchemaVerifier(f.dataSource).verify());}
        try(var f=new MediaUploadWorkflowFixture()) {f.execute("DROP INDEX uk_media_upload_original_folder");assertThrows(IllegalStateException.class,()->new MediaUploadSchemaVerifier(f.dataSource).verify());}
    }
    @Test void authenticatedHttpCommitsMetadataBusinessAndSnapshotTogether() throws Exception {
        try(var f=new MediaUploadWorkflowFixture();var auth=new CatalogMvcTestSupport()){
            var controller=new MediaUploadController(f.service(),new MediaUploadViewProjector((p,k)->"https://example.test/file/"+k.value()),auth.identity());var mvc=auth.build(new Object[]{controller},new MediaUploadExceptionHandler());String token=auth.token(1);var request=MediaUploadWorkflowFixture.request(1,com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot.AUDIO,7);
            String body=new ObjectMapper().writeValueAsString(Map.of("operationId",request.operationId().toString(),"targetType","MUSIC_TRACK","targetId",request.targetId(),"slot","AUDIO","expectedVersion",7,"fileName","song.mp3","fileSize",1,"mediaType","audio/mpeg","sourceSha256",request.file().sourceSha256()));
            mvc.perform(post(BASE).header("Authorization",token).contentType("application/json").content(body)).andExpect(status().isCreated());
            var file=new MockMultipartFile("file","song.mp3","audio/mpeg",new byte[]{0}){@Override public byte[] getBytes(){throw new AssertionError("Tracked upload must stream");}};
            mvc.perform(multipart(BASE+"/"+request.operationId()+"/content").file(file).with(r->{r.setMethod("PUT");return r;}).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED")).andExpect(jsonPath("$.data.result.music.version").value(8));
            mvc.perform(get(BASE).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].operationId").value(request.operationId().toString())).andExpect(jsonPath("$.data.records[0].intent").doesNotExist()).andExpect(jsonPath("$.data.records[0].providerUploadId").doesNotExist());
            assertEquals(8,f.music.findSnapshotById(request.targetId()).orElseThrow().version());assertEquals(List.of("direct"),f.storage.calls);
            mvc.perform(post(BASE+"/"+request.operationId()+"/recover").header("Authorization",token).contentType("application/json").content("{\"operationVersion\":\"0\"}")).andExpect(status().isOk());assertEquals(List.of("direct"),f.storage.calls);
        }
    }
    @Test void oversizedAndCorruptContentNeverDispatch() throws Exception {
        try(var f=new MediaUploadWorkflowFixture();var auth=new CatalogMvcTestSupport()){
            var mvc=auth.build(new Object[]{new MediaUploadController(f.service(),new MediaUploadViewProjector((p,k)->"https://example.test/file/"+k.value()),auth.identity())},new MediaUploadExceptionHandler());String token=auth.token(1);var request=MediaUploadWorkflowFixture.request(1,com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot.AUDIO,7);
            var fields=new HashMap<String,Object>(Map.of("operationId",request.operationId().toString(),"targetType","MUSIC_TRACK","targetId",request.targetId(),"slot","AUDIO","expectedVersion",7,"fileName","song.mp3","fileSize",104857601,"mediaType","audio/mpeg","sourceSha256",request.file().sourceSha256()));
            mvc.perform(post(BASE).header("Authorization",token).contentType("application/json").content(new ObjectMapper().writeValueAsString(fields))).andExpect(status().isPayloadTooLarge());fields.put("fileSize",1);mvc.perform(post(BASE).header("Authorization",token).contentType("application/json").content(new ObjectMapper().writeValueAsString(fields))).andExpect(status().isCreated());
            mvc.perform(multipart(BASE+"/"+request.operationId()+"/content").file(new MockMultipartFile("file","song.mp3","audio/mpeg",new byte[]{1})).with(r->{r.setMethod("PUT");return r;}).header("Authorization",token)).andExpect(status().isUnprocessableEntity());assertTrue(f.storage.calls.isEmpty());
        }
    }
    @Test void databaseUnavailableReturns503WithoutProviderOrJsonFallback() throws Exception {
        try(var f=new MediaUploadWorkflowFixture();var auth=new CatalogMvcTestSupport()) {
            var mvc=auth.build(new Object[]{new MediaUploadController(f.service(),new MediaUploadViewProjector((p,k)->"https://example.test/file/"+k.value()),auth.identity())},new MediaUploadExceptionHandler());
            f.execute("ALTER TABLE media_upload_operation RENAME TO deliberately_unavailable_uploads");
            mvc.perform(get(BASE+"/"+UUID.randomUUID()).header("Authorization",auth.token(1))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));assertTrue(f.storage.calls.isEmpty());
        }
    }
    static String fingerprint(JdbcTemplate jdbc){return jdbc.queryForObject("SELECT md5((SELECT coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY id),'') FROM media_upload_operation t)||(SELECT coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY id),'') FROM music_track t)||(SELECT coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY id),'') FROM media_asset t)||(SELECT coalesce(string_agg(conname||pg_get_constraintdef(oid),'|' ORDER BY conname),'') FROM pg_constraint WHERE conrelid='media_upload_operation'::regclass))",String.class);}
}
