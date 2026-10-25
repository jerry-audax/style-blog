package com.blogsystem.app.it.trigger.http.content;

import com.blogsystem.application.content.service.ContentApplicationFacade;
import com.blogsystem.domain.content.query.TagView;
import com.blogsystem.trigger.http.content.ContentController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TagListIdContractTest {
    @Test void tagIdsAreDecimalStringsSoSelectorsAndPublicListsDoNotLosePrecision() throws Exception {
        var content=mock(ContentApplicationFacade.class);
        when(content.listTag()).thenReturn(List.of(new TagView(9007199254740993L,"long ID","unrelated-slug",null,0,1,null,null,0)));
        MockMvcBuilders.standaloneSetup(new ContentController(content)).build()
                .perform(get("/api/content/tag/list")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").isString())
                .andExpect(jsonPath("$.data[0].id").value("9007199254740993"));
    }
}
