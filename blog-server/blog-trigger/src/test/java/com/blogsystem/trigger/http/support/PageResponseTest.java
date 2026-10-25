package com.blogsystem.trigger.http.support;

import com.blogsystem.trigger.http.support.PageResponse;

import org.assertj.core.api.Assertions;

import com.blogsystem.types.model.PageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PageResponseTest {
    @Test
    void paginationKeepsExistingHttpNamesAndCeilingPages() throws Exception {
        var json = new ObjectMapper().valueToTree(PageResponse.from(new PageResult<>(List.of("a"), 2, 10, 21)));
        assertThat(json.get("current").longValue()).isEqualTo(2);
        assertThat(json.get("size").longValue()).isEqualTo(10);
        assertThat(json.get("pages").longValue()).isEqualTo(3);
        assertThat(json.get("total").longValue()).isEqualTo(21);
        assertThat(json.get("records").get(0).asText()).isEqualTo("a");
        assertThat(json.has("pageNumber")).isFalse();
    }

    @Test
    void emptyResultHasZeroPages() {
        assertThat(PageResponse.from(new PageResult<>(List.of(), 1, 10, 0)).pages()).isZero();
    }
}
