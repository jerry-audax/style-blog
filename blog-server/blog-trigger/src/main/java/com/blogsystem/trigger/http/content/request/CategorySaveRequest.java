package com.blogsystem.trigger.http.content.request;

import jakarta.validation.constraints.NotBlank;

public record CategorySaveRequest(
        Long id,
        @NotBlank(message = "分类名不能为空") String name,
        @NotBlank(message = "slug不能为空") String slug,
        String description,
        Integer sort,
        Integer status) {
    public com.blogsystem.domain.content.model.CategoryDefinition toDefinition() {
        return new com.blogsystem.domain.content.model.CategoryDefinition(
                id, name, slug, description, sort, status);
    }
}
