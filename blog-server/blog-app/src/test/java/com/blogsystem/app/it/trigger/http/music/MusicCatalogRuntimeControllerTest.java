package com.blogsystem.app.it.trigger.http.music;

import com.blogsystem.trigger.http.music.MusicAdminController;
import com.blogsystem.trigger.http.music.MusicExceptionHandler;

import com.blogsystem.application.music.port.MusicCatalogUseCase;
import com.blogsystem.application.music.service.MusicApplicationService;
import com.blogsystem.application.music.service.MusicCatalogApplicationService;

import com.blogsystem.domain.music.model.MusicCatalogException;

import com.blogsystem.application.music.service.MusicCatalogApplicationService.TrackView;
import com.blogsystem.domain.music.model.MusicCatalogException.Reason;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MusicCatalogRuntimeControllerTest {
    CatalogMvcTestSupport auth;MockMvc mvc;String token;
    @BeforeEach void setup(){
        auth=new CatalogMvcTestSupport();token=auth.token(1);var catalog=mock(MusicCatalogUseCase.class);
        when(catalog.updateMetadata(anyString(),any(),any(),any(),anyBoolean(),anyInt(),nullable(Long.class))).thenAnswer(call->{
            Long version=call.getArgument(6);if(version==null)throw new MusicCatalogException(MusicCatalogException.Reason.MISSING_VERSION);
            if("unknown".equals(call.getArgument(0)))throw new MusicCatalogException(MusicCatalogException.Reason.NOT_FOUND);
            if(version==7)throw new MusicCatalogException(MusicCatalogException.Reason.CONFLICT);
            return new MusicCatalogApplicationService.TrackView(call.getArgument(0),"保存","",null,true,0,null,null,null,version+1);});
        doThrow(new MusicCatalogException(MusicCatalogException.Reason.OPERATION_NOT_READY)).when(catalog).deleteTrack(anyString());
        when(catalog.upload(anyString(),any())).thenThrow(new MusicCatalogException(MusicCatalogException.Reason.OPERATION_NOT_READY));
        when(catalog.deleteMedia(anyString(),any())).thenThrow(new MusicCatalogException(MusicCatalogException.Reason.OPERATION_NOT_READY));
        mvc=auth.build(new Object[]{new MusicAdminController(mock(MusicApplicationService.class),catalog)},new MusicExceptionHandler());
    }
    @AfterEach void close(){auth.close();}
    @Test void zeroVersionIsNotMissingAndResponseCarriesNewVersion() throws Exception {
        mvc.perform(put("/api/admin/music/tracks/track-1").header("Authorization",token).contentType("application/json").content("{\"title\":\"保存\",\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.version").value(1));
    }
    @Test void strictVersionsAndSortOrdersCannotBeCoerced() throws Exception {
        for(String value:new String[]{"7.5","\"7\"","-1","true","{}","9223372036854775808"})
            mvc.perform(put("/api/admin/music/tracks/track-1").header("Authorization",token).contentType("application/json").content("{\"title\":\"x\",\"expectedVersion\":"+value+"}"))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value(422));
        for(String value:new String[]{"1.5","-1","100001","\"1\""})
            mvc.perform(put("/api/admin/music/tracks/track-1").header("Authorization",token).contentType("application/json").content("{\"title\":\"x\",\"sortOrder\":"+value+",\"expectedVersion\":0}"))
                    .andExpect(status().isUnprocessableEntity());
    }
    @Test void missingStaleAndUnknownAreDistinct() throws Exception {
        mvc.perform(put("/api/admin/music/tracks/track-1").header("Authorization",token).contentType("application/json").content("{\"title\":\"x\"}"))
                .andExpect(status().is(428)).andExpect(jsonPath("$.code").value(428));
        mvc.perform(put("/api/admin/music/tracks/track-1").header("Authorization",token).contentType("application/json").content("{\"title\":\"x\",\"expectedVersion\":7}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        mvc.perform(put("/api/admin/music/tracks/unknown").header("Authorization",token).contentType("application/json").content("{\"title\":\"x\",\"expectedVersion\":0}"))
                .andExpect(status().isNotFound());
    }
    @Test void resourceOperationsNeverReturnFalseSuccess() throws Exception {
        mvc.perform(delete("/api/admin/music/tracks/track-1").header("Authorization",token)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("资源操作尚未接入"));
        mvc.perform(delete("/api/admin/music/tracks/track-1/media/audio").header("Authorization",token)).andExpect(status().isServiceUnavailable());
        mvc.perform(multipart("/api/admin/music/tracks/track-1/media/audio").file(new MockMultipartFile("file","x.mp3","audio/mpeg",new byte[]{1})).header("Authorization",token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
    }
    @Test void anonymousAndHistoricalOwnerCannotWrite() throws Exception {
        mvc.perform(put("/api/admin/music/tracks/track-1").contentType("application/json").content("{\"title\":\"x\",\"expectedVersion\":0}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/music/tracks/track-1").header("Authorization",auth.token(2)).contentType("application/json").content("{\"title\":\"x\",\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
    }
}
