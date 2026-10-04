package com.blogsystem.ai.application.conversation;

public record ChatCommand(String principalId, String conversationId, String message) {
    public ChatCommand {
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("principalId is required");
        if (conversationId == null || conversationId.isBlank())
            throw new IllegalArgumentException("conversationId is required");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
    }
}
