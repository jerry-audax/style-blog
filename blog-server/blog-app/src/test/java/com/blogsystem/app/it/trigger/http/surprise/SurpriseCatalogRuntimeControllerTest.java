package com.blogsystem.app.it.trigger.http.surprise;

import com.blogsystem.trigger.http.surprise.SurpriseAdminController;
import com.blogsystem.trigger.http.surprise.SurpriseExceptionHandler;
import com.blogsystem.trigger.http.surprise.SurprisePublicController;

import com.blogsystem.application.surprise.port.SurpriseVideoUseCase;
import com.blogsystem.application.surprise.result.SurpriseVideoAdminView;
import com.blogsystem.application.surprise.service.SurpriseVideoApplicationService;

import com.blogsystem.domain.surprise.model.SurpriseCatalogException;

import com.blogsystem.application.surprise.service.SurpriseVideoApplicationService.VideoView;
import com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SurpriseCatalogRuntimeControllerTest {
    CatalogMvcTestSupport auth;MockMvc mvc;String token;
    @BeforeEach void setup(){
        auth=new CatalogMvcTestSupport();token=auth.token(1);var service=mock(SurpriseVideoUseCase.class);
        when(service.adminCatalog()).thenReturn(List.of(new SurpriseVideoAdminView("video","视频","https://example.test/file/blog/v","blog/v",null,null,true,0,0L)));
        when(service.randomPublic()).thenReturn(new SurpriseVideoApplicationService.VideoView("video","视频","https://example.test/file/blog/v","blog/v","",0,true,0));
        when(service.updateMetadata(anyString(),any(),any(),any(),nullable(Long.class))).thenAnswer(call->{
            Long v=call.getArgument(4);if(v==null)throw new SurpriseCatalogException(SurpriseCatalogException.Reason.MISSING_VERSION);
            if("missing".equals(call.getArgument(0)))throw new SurpriseCatalogException(SurpriseCatalogException.Reason.NOT_FOUND);
            if(v==7)throw new SurpriseCatalogException(SurpriseCatalogException.Reason.CONFLICT);
            return new SurpriseVideoAdminView(call.getArgument(0),"保存","https://example.test/file/blog/v","blog/v",null,null,true,0,v+1);});
        when(service.create(anyString(),anyBoolean(),anyInt(),any())).thenThrow(new SurpriseCatalogException(SurpriseCatalogException.Reason.OPERATION_NOT_READY));
        doThrow(new SurpriseCatalogException(SurpriseCatalogException.Reason.OPERATION_NOT_READY)).when(service).delete(anyString());
        mvc=auth.build(new Object[]{new SurpriseAdminController(service),new SurprisePublicController(service)},new SurpriseExceptionHandler());
    }
    @AfterEach void close(){auth.close();}
    @Test void publicResponseDoesNotExposeAdminVersionAndUnknownSizeStaysNull() throws Exception {
        mvc.perform(get("/api/surprise/random")).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").doesNotExist()).andExpect(jsonPath("$.data.size").value(0));
        mvc.perform(get("/api/admin/surprise/videos").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].version").value(0)).andExpect(jsonPath("$.data[0].size").doesNotExist());
    }
    @Test void versionsAndSortCannotBeSilentlyTruncated() throws Exception {
        mvc.perform(put("/api/admin/surprise/videos/video").header("Authorization",token).contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        for(String v:new String[]{"7.5","\"0\"","-1"})mvc.perform(put("/api/admin/surprise/videos/video").header("Authorization",token).contentType("application/json").content("{\"expectedVersion\":"+v+"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/admin/surprise/videos/video").header("Authorization",token).contentType("application/json").content("{\"sortOrder\":0.5,\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }
    @Test void versionErrorsAndUnknownIdKeepStatusContract() throws Exception {
        mvc.perform(put("/api/admin/surprise/videos/video").header("Authorization",token).contentType("application/json").content("{}")) .andExpect(status().is(428));
        mvc.perform(put("/api/admin/surprise/videos/video").header("Authorization",token).contentType("application/json").content("{\"expectedVersion\":7}")) .andExpect(status().isConflict());
        mvc.perform(put("/api/admin/surprise/videos/missing").header("Authorization",token).contentType("application/json").content("{\"expectedVersion\":0}")) .andExpect(status().isNotFound());
    }
    @Test void blockedResourceCallsAre503AndAuthStillApplies() throws Exception {
        mvc.perform(delete("/api/admin/surprise/videos/video").header("Authorization",token)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("资源操作尚未接入"));
        mvc.perform(multipart("/api/admin/surprise/videos").file(new MockMultipartFile("file","x.mp4","video/mp4",new byte[]{1})).header("Authorization",token))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/admin/surprise/videos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/surprise/videos").header("Authorization",auth.token(2))).andExpect(status().isForbidden());
    }
}
