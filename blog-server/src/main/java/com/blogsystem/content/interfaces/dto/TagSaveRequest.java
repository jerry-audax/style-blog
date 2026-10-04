package com.blogsystem.content.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record TagSaveRequest(
        Long id,
        @NotBlank(message = "标签名不能为空")
        String name,
        @NotBlank(message = "slug不能为空")
        String slug,
        String color,
        Integer sort,
        Integer status
) {
    public com.blogsystem.content.domain.model.TagDefinition toDefinition() {
        return new com.blogsystem.content.domain.model.TagDefinition(id, name, slug, color, sort, status);
    }
}
