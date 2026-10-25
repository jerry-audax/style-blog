package com.blogsystem.domain.content.model;

import com.blogsystem.types.exception.DomainException;

public record CategoryDefinition(
        Long id, String name, String slug, String description, Integer sort, Integer status) {
    public CategoryDefinition {
        if (name == null || name.isBlank() || slug == null || slug.isBlank())
            throw new DomainException("分类名称和标识不能为空");
        sort = sort == null ? 0 : sort;
        status = status == null ? 1 : status;
    }
}
