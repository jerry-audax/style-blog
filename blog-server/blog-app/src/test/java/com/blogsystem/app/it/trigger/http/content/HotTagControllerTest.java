package com.blogsystem.app.it.trigger.http.content;

import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import com.blogsystem.application.content.service.ContentApplicationFacade;
import com.blogsystem.domain.content.model.TagDefinition;
import com.blogsystem.domain.content.query.TagView;
import com.blogsystem.trigger.http.content.ContentController;
import com.blogsystem.trigger.http.support.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HotTagControllerTest {
    @Test void homeResponseReturnsResolvedTagsAndSafeLongIdsWithoutRequiringOwnerLogin() throws Exception {
        try(var auth=new CatalogMvcTestSupport()) {
            var service=mock(ContentApplicationFacade.class);
            var tag=new TagView(9007199254740993L,"数据库","database",null,0,1,null,null,0,0);
            when(service.listHomeTags()).thenReturn(List.of(tag));when(service.listTag()).thenReturn(List.of(tag));
            var mvc=auth.build(new Object[]{new ContentController(service)},new GlobalExceptionHandler());
            mvc.perform(get("/api/content/home-tags"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(jsonPath("$.data.tags[0].id").value("9007199254740993"))
                    .andExpect(jsonPath("$.data.tags[0].position").value(1))
                    .andExpect(jsonPath("$.data.tags[0].href").value("/blog/tags/?tagId=9007199254740993"));
            mvc.perform(get("/api/content/tag/list")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].isHot").value(0));
        }
    }
    @Test void hotFlagUsesExistingOwnerProtectedTagWriteAndValidatesItsRange() throws Exception {
        try(var auth=new CatalogMvcTestSupport()) {
            var service=mock(ContentApplicationFacade.class);when(service.saveTag(any())).thenReturn(1L);
            var mvc=auth.build(new Object[]{new ContentController(service)},new GlobalExceptionHandler());
            String body="{\"id\":\"9007199254740993\",\"name\":\"tag\",\"slug\":\"tag\",\"isHot\":1}";
            mvc.perform(post("/api/content/tag").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/content/tag").header("Authorization",auth.token(2)).contentType("application/json").content(body)).andExpect(status().isForbidden());
            String token=auth.token(1);
            mvc.perform(post("/api/content/tag").header("Authorization",token).contentType("application/json").content(body)).andExpect(status().isOk());
            var captured=ArgumentCaptor.forClass(TagDefinition.class);verify(service).saveTag(captured.capture());
            assertEquals(9007199254740993L,captured.getValue().id());assertEquals(1,captured.getValue().isHot());
            for(int invalid:List.of(-1,2))mvc.perform(post("/api/content/tag").header("Authorization",token).contentType("application/json")
                    .content(body.replace("\"isHot\":1","\"isHot\":"+invalid))).andExpect(status().isBadRequest());
        }
    }
    @Test void databaseFailureIsNotReportedAsAnEmptySuccessfulHomepage() throws Exception {
        try(var auth=new CatalogMvcTestSupport()) {
            var service=mock(ContentApplicationFacade.class);when(service.listHomeTags()).thenThrow(new IllegalStateException("database unavailable"));
            var mvc=auth.build(new Object[]{new ContentController(service)},new GlobalExceptionHandler());
            mvc.perform(get("/api/content/home-tags")).andExpect(status().is5xxServerError());
        }
    }
}
