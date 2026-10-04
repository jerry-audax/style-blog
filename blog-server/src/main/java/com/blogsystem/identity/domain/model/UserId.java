package com.blogsystem.identity.domain.model;

import com.blogsystem.shared.domain.Identifier;

public record UserId(Long value) implements Identifier<Long> {
    public UserId {
        Identifier.requireValue(value);
    }
}
