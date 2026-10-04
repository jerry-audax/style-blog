package com.blogsystem.ai.domain.conversation;

import java.time.Instant;
import java.util.Objects;

public record ChatMessage(Role role, String content, Instant occurredAt) {
    public ChatMessage {
        Objects.requireNonNull(role);
        if (content == null || content.isBlank()) throw new IllegalArgumentException("message content is required");
        Objects.requireNonNull(occurredAt);
    }

    public static ChatMessage user(String content, Instant occurredAt) {
        return new ChatMessage(Role.USER, content, occurredAt);
    }

    public static ChatMessage assistant(String content, Instant occurredAt) {
        return new ChatMessage(Role.ASSISTANT, content, occurredAt);
    }

    public enum Role {USER, ASSISTANT}
}
