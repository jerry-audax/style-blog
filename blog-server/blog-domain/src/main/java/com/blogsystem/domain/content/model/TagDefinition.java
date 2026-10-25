package com.blogsystem.domain.content.model;

import com.blogsystem.types.exception.DomainException;

public record TagDefinition(
        Long id, String name, String slug, String color, Integer sort, Integer status, Integer isHot) {
    public TagDefinition {
        if (name == null || name.isBlank() || slug == null || slug.isBlank())
            throw new DomainException("标签名称和标识不能为空");
        sort = sort == null ? 0 : sort;
        status = status == null ? 1 : status;
        if (isHot != null && isHot != 0 && isHot != 1)
            throw new DomainException("热门标签标记只能是0或1");
    }

    public TagDefinition(Long id, String name, String slug, String color, Integer sort, Integer status) {
        this(id, name, slug, color, sort, status, null);
    }
}
