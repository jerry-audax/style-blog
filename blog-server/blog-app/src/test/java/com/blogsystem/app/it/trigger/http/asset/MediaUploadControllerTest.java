package com.blogsystem.app.it.trigger.http.asset;

import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;

import com.blogsystem.trigger.http.asset.MediaUploadController;
import com.blogsystem.trigger.http.asset.MediaUploadExceptionHandler;

import com.blogsystem.application.asset.upload.assembler.MediaUploadViewProjector;
import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService.Submission;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Outcome;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation.Progress;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaUploadControllerTest {
    @Test void unexpectedFailureCannotLeakCredentialsOrProviderSession() throws Exception {
        try(var auth=new CatalogMvcTestSupport()) {
            var service=mock(MediaUploadApplicationService.class);when(service.get(anyLong(),any())).thenThrow(new IllegalStateException("Bearer fake-token fake-session private-provider-body"));
            String body=mvc(auth,service).perform(get(BASE+"/"+ID).header("Authorization",auth.token(1))).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
            org.junit.jupiter.api.Assertions.assertFalse(body.contains("fake-token"));org.junit.jupiter.api.Assertions.assertFalse(body.contains("fake-session"));org.junit.jupiter.api.Assertions.assertFalse(body.contains("provider-body"));
        }
    }
    static final String BASE="/api/admin/media/uploads",ID="00000000-0000-0000-0000-000000000401";
    static final String CREATE="{\"operationId\":\""+ID+"\",\"targetType\":\"MUSIC_TRACK\",\"targetId\":\"song\",\"slot\":\"AUDIO\",\"expectedVersion\":0,\"fileName\":\"song.mp3\",\"fileSize\":1,\"mediaType\":\"audio/mpeg\",\"sourceSha256\":\""+"a".repeat(64)+"\"}";
    static MediaUploadOperation ready(){var file=new MediaUploadFile("song.mp3","audio/mpeg",1,"a".repeat(64));return MediaUploadPolicy.ready(1,new MediaUploadRequest(UUID.fromString(ID),MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.AUDIO,0L,file,null),new MediaUploadTarget(MediaUploadTargetType.MUSIC_TRACK,"song",MediaUploadSlot.AUDIO,0L,null,null),"cloudflare_imgbed",new MediaObjectKey("blog"),UUID.randomUUID(),Instant.EPOCH);}
    MockMvc mvc(CatalogMvcTestSupport auth,MediaUploadApplicationService service){return auth.build(new Object[]{new MediaUploadController(service,new MediaUploadViewProjector((p,k)->"https://example.test/file/"+k.value()),auth.identity())},new MediaUploadExceptionHandler());}
    @Test void ownerOnlyAndNoStore() throws Exception {
        try(var auth=new CatalogMvcTestSupport()){
            var service=mock(MediaUploadApplicationService.class);var mvc=mvc(auth,service);String visitor=auth.token(2);
            var routes=List.of(get(BASE),get(BASE+"/"+ID),post(BASE).content(CREATE).contentType("application/json"),put(BASE+"/"+ID+"/content"),post(BASE+"/"+ID+"/pause").content("{\"operationVersion\":\"0\"}").contentType("application/json"),post(BASE+"/"+ID+"/recover").content("{\"operationVersion\":\"0\"}").contentType("application/json"),post(BASE+"/"+ID+"/bind").content("{}").contentType("application/json"));
            for(var route:routes){mvc.perform(route).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(route.header("Authorization",visitor)).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));}
            verifyNoInteractions(service);
        }
    }
    @Test void sameCreateReturns201Then200() throws Exception {
        try(var auth=new CatalogMvcTestSupport()){
            var service=mock(MediaUploadApplicationService.class);when(service.create(eq(1L),any())).thenReturn(new MediaUploadApplicationService.Submission(ready(),true),new MediaUploadApplicationService.Submission(ready(),false));
            var mvc=mvc(auth,service);String token=auth.token(1);
            mvc.perform(post(BASE).header("Authorization",token).contentType("application/json").content(CREATE)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.operationVersion").value("0"));
            mvc.perform(post(BASE).header("Authorization",token).contentType("application/json").content(CREATE)).andExpect(status().isOk());
            var capture=org.mockito.ArgumentCaptor.forClass(MediaUploadRequest.class);verify(service,times(2)).create(eq(1L),capture.capture());assertEquals(0L,capture.getValue().expectedVersion());
        }
    }
    @Test void strictVersionsIdsAndSizes() throws Exception {
        try(var auth=new CatalogMvcTestSupport()){
            var service=mock(MediaUploadApplicationService.class);var mvc=mvc(auth,service);String token=auth.token(1);
            for(String value:List.of("0","0.5","-1","\"-1\"","\"01\"","\"9223372036854775808\"","true"))mvc.perform(post(BASE+"/"+ID+"/recover").header("Authorization",token).contentType("application/json").content("{\"operationVersion\":"+value+"}")).andExpect(status().isUnprocessableEntity());
            mvc.perform(post(BASE+"/"+ID+"/recover").header("Authorization",token).contentType("application/json").content("{}")).andExpect(status().is(428));
            for(String value:List.of("\"1\"","1.1","0","-1","9223372036854775808","true"))mvc.perform(post(BASE).header("Authorization",token).contentType("application/json").content(CREATE.replace("\"fileSize\":1","\"fileSize\":"+value))).andExpect(status().isUnprocessableEntity());
            mvc.perform(post(BASE).header("Authorization",token).contentType("application/json").content(CREATE.replace("\"expectedVersion\":0,",""))).andExpect(status().is(428));
            for(String id:List.of("1-1-1-1-1","not-a-uuid"))mvc.perform(get(BASE+"/"+id).header("Authorization",token)).andExpect(status().isUnprocessableEntity());
            var op=ready();when(service.recover(1,UUID.fromString(ID),9007199254740993L)).thenReturn(new MediaUploadOperation(op.intent(),op.progress(),op.lease(),op.outcome(),9007199254740993L,op.createdAt(),op.updatedAt()));
            mvc.perform(post(BASE+"/"+ID+"/recover").header("Authorization",token).contentType("application/json").content("{\"operationVersion\":\"9007199254740993\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.operationVersion").value("9007199254740993"));
        }
    }
    @Test void acceptedIsNotCompleted() throws Exception {
        try(var auth=new CatalogMvcTestSupport()){
            var service=mock(MediaUploadApplicationService.class);var op=ready();var pending=new MediaUploadOperation(op.intent(),new MediaUploadOperation.Progress(MediaUploadStatus.UNKNOWN,MediaUploadStage.DIRECT,null,0,null,false,1,0,0),op.lease(),new MediaUploadOperation.Outcome(null,MediaUploadErrorCode.PROVIDER_UNKNOWN,null,null),3,op.createdAt(),op.updatedAt());
            when(service.get(1,UUID.fromString(ID))).thenReturn(pending);var mvc=mvc(auth,service);
            mvc.perform(get(BASE+"/"+ID).header("Authorization",auth.token(1))).andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.status").value("UNKNOWN")).andExpect(jsonPath("$.data.result").isEmpty()).andExpect(jsonPath("$.data.providerUploadId").doesNotExist()).andExpect(jsonPath("$.data.intent").doesNotExist());
        }
    }
    private static void assertEquals(Object expected,Object actual){org.junit.jupiter.api.Assertions.assertEquals(expected,actual);}
}
