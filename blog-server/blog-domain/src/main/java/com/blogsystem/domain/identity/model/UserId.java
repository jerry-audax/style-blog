package com.blogsystem.domain.identity.model;

import com.blogsystem.types.model.Identifier;

public record UserId(Long value) implements Identifier<Long> {
    public UserId {
        Identifier.requireValue(value);
    }
}
