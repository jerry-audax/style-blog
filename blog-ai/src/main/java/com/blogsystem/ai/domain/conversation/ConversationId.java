package com.blogsystem.ai.domain.conversation;

import java.util.Objects;

public record ConversationId(String principalId, String value) {
    public ConversationId {
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("principalId is required");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("conversationId is required");
    }
}
