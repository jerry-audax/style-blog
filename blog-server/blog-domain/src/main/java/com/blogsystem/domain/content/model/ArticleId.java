package com.blogsystem.domain.content.model;

import com.blogsystem.types.model.Identifier;

public record ArticleId(Long value) implements Identifier<Long> {

    public ArticleId {
        Identifier.requireValue(value);
    }
}
