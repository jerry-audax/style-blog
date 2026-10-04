package com.blogsystem.ai.application;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@EnabledAi
public class AiApplicationFacade {
    private final AiChatPort chatPort;
    private final ConversationMemoryPort memoryPort;
    private final KnowledgeIndexPort indexPort;

    public AiApplicationFacade(AiChatPort chatPort, ConversationMemoryPort memoryPort, KnowledgeIndexPort indexPort) {
        this.chatPort = chatPort;
        this.memoryPort = memoryPort;
        this.indexPort = indexPort;
    }

    public Flux<String> chat(String principalId, String message, String sessionId) {
        return chatPort.stream(principalId, sessionId, message);
    }

    public void clearMemory(String sessionId) {
        memoryPort.clear(sessionId);
    }

    public int rebuildVectors() {
        return indexPort.rebuild();
    }
}
