package com.blogsystem.app.it.trigger.http.asset;

import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;

import com.blogsystem.trigger.http.asset.MediaCatalogCapabilitiesController;
import com.blogsystem.trigger.http.support.GlobalExceptionHandler;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.blogsystem.application.asset.metadata.result.MediaCatalogCapabilities;
import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaCatalogCapabilitiesControllerTest {
    @Test void trackedProtocolDisclosesOnlyFixedLimitsAndNeverDelete() throws Exception {
        try(var auth=new CatalogMvcTestSupport()) {
            var cap=new MediaCatalogCapabilities(true,true,true,true,false,false,"tracked-v1",new com.blogsystem.application.asset.upload.result.MediaUploadLimits(104857600,10485760,524288,209715200,16000000));
            var mvc=auth.build(new Object[]{new MediaCatalogCapabilitiesController(cap)},new GlobalExceptionHandler());
            mvc.perform(get("/api/admin/media/capabilities").header("Authorization",auth.token(1))).andExpect(status().isOk()).andExpect(jsonPath("$.data.uploadProtocol").value("tracked-v1")).andExpect(jsonPath("$.data.uploadLimits.videoBytes").value(209715200)).andExpect(jsonPath("$.data.mediaDelete").value(false)).andExpect(jsonPath("$.data.catalogDelete").value(false)).andExpect(jsonPath("$.data.token").doesNotExist());
        }
    }
    @Test void ownerCapabilitiesDoNotDiscloseModeOrConfiguration() throws Exception {
        try(var auth=new CatalogMvcTestSupport()) {
            var mvc=auth.build(new Object[]{new MediaCatalogCapabilitiesController(new MediaCatalogCapabilities(true,true,false,false,false,false))},new GlobalExceptionHandler());
            mvc.perform(get("/api/admin/media/capabilities").header("Authorization",auth.token(1))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(jsonPath("$.data.metadataVersionRequired").value(true)).andExpect(jsonPath("$.data.mediaUpload").value(false))
                    .andExpect(jsonPath("$.data.storage").doesNotExist()).andExpect(jsonPath("$.data.dbUrl").doesNotExist());
            mvc.perform(get("/api/admin/media/capabilities")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/media/capabilities").header("Authorization",auth.token(2))).andExpect(status().isForbidden());
        }
    }
}
