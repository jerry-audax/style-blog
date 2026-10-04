package com.blogsystem.content.domain.model;

import com.blogsystem.shared.domain.DomainException;

public record TagDefinition(Long id, String name, String slug, String color, Integer sort, Integer status) {
    public TagDefinition {
        if (name == null || name.isBlank() || slug == null || slug.isBlank())
            throw new DomainException("标签名称和标识不能为空");
        sort = sort == null ? 0 : sort;
        status = status == null ? 1 : status;
    }
}

