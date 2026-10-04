package com.blogsystem.content.domain.model;

import com.blogsystem.shared.domain.Identifier;

public record ArticleId(Long value) implements Identifier<Long> {

    public ArticleId {
        Identifier.requireValue(value);
    }
}
