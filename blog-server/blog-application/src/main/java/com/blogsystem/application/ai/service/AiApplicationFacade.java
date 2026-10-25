package com.blogsystem.application.ai.service;

import com.blogsystem.domain.ai.gateway.AiChatPort;
import com.blogsystem.domain.ai.gateway.ConversationMemoryPort;
import com.blogsystem.domain.ai.gateway.KnowledgeIndexPort;

import java.util.concurrent.Flow;

public class AiApplicationFacade {
    private final AiChatPort chatPort;
    private final ConversationMemoryPort memoryPort;
    private final KnowledgeIndexPort indexPort;

    public AiApplicationFacade(
            AiChatPort chatPort, ConversationMemoryPort memoryPort, KnowledgeIndexPort indexPort) {
        this.chatPort = chatPort;
        this.memoryPort = memoryPort;
        this.indexPort = indexPort;
    }

    public Flow.Publisher<String> chat(String principalId, String message, String sessionId) {
        return chatPort.stream(principalId, sessionId, message);
    }

    public void clearMemory(String sessionId) {
        memoryPort.clear(sessionId);
    }

    public int rebuildVectors() {
        return indexPort.rebuild();
    }

    public void initializeKnowledgeOnStartup() {
        indexPort.initializeOnStartup();
    }
}
