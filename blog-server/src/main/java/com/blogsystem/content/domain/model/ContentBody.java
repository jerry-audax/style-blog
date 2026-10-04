package com.blogsystem.content.domain.model;

import com.blogsystem.shared.domain.DomainException;

import java.util.Objects;

public record ContentBody(String value, ContentFormat format) {

    public ContentBody {
        if (value == null || value.isBlank()) {
            throw new DomainException("文章正文不能为空");
        }
        format = Objects.requireNonNull(format, "content format must not be null");
    }
}
