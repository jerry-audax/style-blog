package com.blogsystem.trigger.http.content.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

public record TagSaveRequest(
        Long id,
        @NotBlank(message = "标签名不能为空") String name,
        @NotBlank(message = "slug不能为空") String slug,
        String color,
        Integer sort,
        Integer status,
        @Min(0) @Max(1) Integer isHot) {
    public com.blogsystem.domain.content.model.TagDefinition toDefinition() {
        return new com.blogsystem.domain.content.model.TagDefinition(
                id, name, slug, color, sort, status, isHot);
    }
}
