package com.blogsystem.app.it.trigger.http.music;

import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import com.blogsystem.application.music.service.MusicCoverBackfillApplicationService;
import com.blogsystem.application.asset.upload.assembler.MediaUploadViewProjector;
import com.blogsystem.trigger.http.music.MusicCoverBackfillController;
import com.blogsystem.trigger.http.music.MusicExceptionHandler;
import com.blogsystem.trigger.http.asset.MediaUploadExceptionHandler;
import com.blogsystem.trigger.http.support.GlobalExceptionHandler;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.valobj.*;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MusicCoverBackfillControllerTest {
    @Test void ownerCanSubmitWhileVisitorsCannotTriggerProviderWrites() throws Exception {
        try(var auth=new CatalogMvcTestSupport()) {
            var service=mock(MusicCoverBackfillApplicationService.class);
            var controller=new MusicCoverBackfillController(service,new MediaUploadViewProjector((p,k)->"https://example.test/file/"+k.value()),auth.identity());
            var mvc=auth.build(new Object[]{controller},new MusicExceptionHandler(),new MediaUploadExceptionHandler(),new GlobalExceptionHandler());
            UUID id=UUID.randomUUID();
            String body="{\"operationId\":\""+id+"\",\"expectedVersion\":0}";
            mvc.perform(post("/api/admin/music/tracks/song/cover/backfill").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
            verifyNoInteractions(service);
            var request=new MediaUploadRequest(id,MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.COVER,0L,new MediaUploadFile("123.jpg","image/jpeg",3,"a".repeat(64)),null);
            var op=MediaUploadPolicy.ready(1,request,new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.COVER,0L,null,null),"cloudflare_imgbed",new MediaObjectKey("blog"),UUID.randomUUID(),Instant.EPOCH);
            when(service.complete(1,"song",0L,id)).thenReturn(op);
            mvc.perform(post("/api/admin/music/tracks/song/cover/backfill").header("Authorization",auth.token(1)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.targetId").value("song")).andExpect(jsonPath("$.data.operationId").value(id.toString()));
            mvc.perform(post("/api/admin/music/tracks/song/cover/backfill").header("Authorization",auth.token(1)).contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\""+id+"\",\"expectedVersion\":\"0\"}"))
                    .andExpect(status().isUnprocessableEntity());
            for (var failure : java.util.Map.of(
                    com.blogsystem.domain.music.model.MusicCatalogException.Reason.CONFLICT,409,
                    com.blogsystem.domain.music.model.MusicCatalogException.Reason.MISSING_VERSION,428,
                    com.blogsystem.domain.music.model.MusicCatalogException.Reason.NOT_FOUND,404).entrySet()) {
                doThrow(new com.blogsystem.domain.music.model.MusicCatalogException(failure.getKey())).when(service).complete(1,"song",0L,id);
                mvc.perform(post("/api/admin/music/tracks/song/cover/backfill").header("Authorization",auth.token(1)).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().is(failure.getValue()));
            }
        }
    }
}
