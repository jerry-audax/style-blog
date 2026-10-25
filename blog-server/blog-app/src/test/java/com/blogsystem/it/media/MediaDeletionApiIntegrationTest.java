package com.blogsystem.it.media;

import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import com.blogsystem.application.asset.deletion.command.MediaDeletionCommand;
import com.blogsystem.application.asset.deletion.service.MediaDeletionApplicationService;
import com.blogsystem.application.asset.metadata.result.MediaCatalogCapabilities;
import com.blogsystem.application.asset.image.service.ImageApplicationService;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionTarget;
import com.blogsystem.domain.asset.image.gateway.ImageStorageGateway;
import com.blogsystem.infrastructure.gateway.imgbed.asset.DeletionGuardedImageStorageGateway;
import com.blogsystem.trigger.http.asset.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaDeletionApiIntegrationTest {
    final ObjectMapper json=new ObjectMapper();
    static final String ROOT="/api/admin/media/deletions";
    static final String TARGET="{\"kind\":\"MUSIC_MEDIA\",\"id\":\"  Existing-视频-ID  \",\"slot\":\"COVER\"}";
    MockMvc mvc(MediaDeletionWorkflowFixture f,CatalogMvcTestSupport auth){return mvc(f.service,auth);}
    MockMvc mvc(MediaDeletionApplicationService service,CatalogMvcTestSupport auth){return auth.build(new Object[]{new MediaDeletionController(service,auth.identity())},new MediaDeletionExceptionHandler());}
    String payload(String version){return "{\"target\":"+TARGET+",\"expectedVersion\":\""+version+"\",\"assetVersions\":{\""+MediaRuntimeRepositoryIntegrationTest.IMAGE+"\":\"0\"}}";}
    @Test void ownerPreviewSubmitReplayAndHistoryUseSafeNoStoreViews() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture();var auth=new CatalogMvcTestSupport()){
            var mvc=mvc(f,auth);String token=auth.token(1),key=UUID.randomUUID().toString();
            mvc.perform(post(ROOT+"/preview").header("Authorization",token).contentType("application/json").content("{\"target\":"+TARGET+"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.expectedVersion").value("7")).andExpect(jsonPath("$.data.candidates[0].assetVersion").value("0"))
                .andExpect(jsonPath("$.data.referenceCoverageComplete").value(false)).andExpect(jsonPath("$.data.environmentId").value("local"));
            assertEquals(0,f.service.history(1,0,20).total());
            var result=mvc.perform(post(ROOT).header("Authorization",token).header("Idempotency-Key",key).contentType("application/json").content(payload("7")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.originalVersion").value("7"))
                .andExpect(jsonPath("$.data.localRemovalCommitted").value(true)).andExpect(jsonPath("$.data.allFilesDeleted").value(false))
                .andExpect(jsonPath("$.data.items[0].status").value("RETAINED")).andReturn();
            String body=result.getResponse().getContentAsString(),id=json.readTree(body).at("/data/operationId").asText();
            assertFalse(body.contains("/file/"));assertFalse(body.contains("blog/images"));assertFalse(body.contains("ownershipNote"));assertFalse(body.contains("fingerprint"));
            mvc.perform(post(ROOT).header("Authorization",token).header("Idempotency-Key",key).contentType("application/json").content(payload("7")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.operationId").value(id));
            mvc.perform(get(ROOT+"/"+id).header("Authorization",token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(get(ROOT).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
            assertNull(f.music.findSnapshotById(MediaDeletionWorkflowIntegrationTest.SONG).orElseThrow().track().coverAssetId());
        }
    }
    @Test void unauthenticatedNonOwnerAndOtherActorReceiptsAreNotExposed() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture();var auth=new CatalogMvcTestSupport()){
            var mvc=mvc(f,auth);
            mvc.perform(get(ROOT)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(get(ROOT).header("Authorization",auth.token(2))).andExpect(status().isForbidden());
            var op=f.service.submit(7,UUID.randomUUID().toString(),MediaDeletionWorkflowIntegrationTest.command(f,MediaDeletionWorkflowIntegrationTest.TRACK));
            mvc.perform(get(ROOT+"/"+op.id()).header("Authorization",auth.token(1))).andExpect(status().isNotFound());
        }
    }
    @Test void validationRejectsMissingMalformedOverflowNumericAndInjectedFields() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture();var auth=new CatalogMvcTestSupport()){
            var mvc=mvc(f,auth);String token=auth.token(1);
            mvc.perform(post(ROOT).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"target\":"+TARGET+",\"assetVersions\":{}}"))
                .andExpect(status().is(428));
            for(String body:List.of(payload("9223372036854775808"),payload("-1"),payload("01"),payload("7").replace("\"7\"","7"),payload("7").replace("\"0\"","0"),payload("7").replace("{\"target\"","{\"actorId\":1,\"target\""),payload("7").replace("\"slot\":\"COVER\"","\"slot\":\"COVER\",\"environmentId\":\"production\""))){
                mvc.perform(post(ROOT).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity()).andExpect(header().string("Cache-Control","no-store"));
            }
            mvc.perform(post(ROOT).header("Authorization",token).contentType("application/json").content(payload("7"))).andExpect(status().isUnprocessableEntity());
            mvc.perform(get(ROOT).param("limit","101").header("Authorization",token)).andExpect(status().isUnprocessableEntity());
            assertEquals(7,f.music.findSnapshotById(MediaDeletionWorkflowIntegrationTest.SONG).orElseThrow().version());
        }
    }
    @Test void staleAndReusedDifferentCommandAreConflicts() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture();var auth=new CatalogMvcTestSupport()){
            var mvc=mvc(f,auth);String token=auth.token(1),key=UUID.randomUUID().toString();
            mvc.perform(post(ROOT).header("Authorization",token).header("Idempotency-Key",key).contentType("application/json").content(payload("6"))).andExpect(status().isConflict());
            mvc.perform(post(ROOT).header("Authorization",token).header("Idempotency-Key",key).contentType("application/json").content(payload("7"))).andExpect(status().isAccepted());
            mvc.perform(post(ROOT).header("Authorization",token).header("Idempotency-Key",key).contentType("application/json").content(payload("8"))).andExpect(status().isConflict());
        }
    }
    @Test void largeVersionIsNeverRoundedAndOwnershipUsesServerActorAndEnvironment() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture();var auth=new CatalogMvcTestSupport()){
            f.jdbc.update("UPDATE music_track SET version=9007199254740993");
            var mvc=mvc(f,auth);String token=auth.token(1);
            mvc.perform(post(ROOT+"/preview").header("Authorization",token).contentType("application/json").content("{\"target\":"+TARGET+"}"))
                .andExpect(jsonPath("$.data.expectedVersion").value("9007199254740993"));
            String url="/api/admin/media/assets/"+MediaRuntimeRepositoryIntegrationTest.IMAGE+"/deletion-ownership";
            mvc.perform(post(url).header("Authorization",token).contentType("application/json").content("{\"scope\":\"EXCLUSIVE\",\"expectedVersion\":\"0\",\"note\":\"confirmed dedicated test file\",\"actorId\":7}"))
                .andExpect(status().isUnprocessableEntity());
            mvc.perform(post(url).header("Authorization",token).contentType("application/json").content("{\"scope\":\"SHARED\",\"expectedVersion\":\"0\",\"note\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assetVersion").value("1"));
            assertEquals(1,f.assets.findById(UUID.fromString(MediaRuntimeRepositoryIntegrationTest.IMAGE)).orElseThrow().ownership().confirmedBy());
            mvc.perform(post(ROOT).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(payload("9007199254740993").replace("\":\"0\"","\":\"1\"")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.originalVersion").value("9007199254740993"));
        }
    }
    @Test void disabledSubmitStillAllowsHistoryButMissingSchemaNeverPretendsEmpty() throws Exception {
        try(var f=new MediaDeletionWorkflowFixture();var auth=new CatalogMvcTestSupport()){
            var disabled=new MediaDeletionApplicationService(f.assets,f.operations,null,null,f.tx,Clock.systemUTC(),null,false,()->true);
            var mvc=mvc(disabled,auth);String token=auth.token(1);
            mvc.perform(get(ROOT).header("Authorization",token)).andExpect(status().isOk());
            mvc.perform(post(ROOT).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(payload("7"))).andExpect(status().isServiceUnavailable());
            var missing=new MediaDeletionApplicationService(f.assets,f.operations,null,null,f.tx,Clock.systemUTC(),null,false,()->false);
            mvc(missing,auth).perform(get(ROOT).header("Authorization",token)).andExpect(status().isServiceUnavailable());
        }
    }
    @Test void postgresImageDeleteCannotBypassGuardEvenThroughOldController() throws Exception {
        try(var auth=new CatalogMvcTestSupport()){
            var provider=mock(ImageStorageGateway.class);
            var gateway=new DeletionGuardedImageStorageGateway(provider,false);
            var mvc=auth.build(new Object[]{new ImageController(new ImageApplicationService(gateway))},new ImageExceptionHandler());
            mvc.perform(delete("/api/images").header("Authorization",auth.token(1)).contentType("application/json").content("{\"path\":\"blog/images/cover.png\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));
            verifyNoInteractions(provider);
        }
    }
}
