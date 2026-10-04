package com.blogsystem.interaction.domain.port;

public interface CommentRateLimit {
    void requireAllowed(long userId);
}
