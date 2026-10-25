package com.blogsystem.domain.interaction.gateway;

public interface CommentRateLimit {
    void requireAllowed(long userId);
}
