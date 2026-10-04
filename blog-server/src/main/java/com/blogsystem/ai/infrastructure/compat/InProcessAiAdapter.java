package com.blogsystem.ai.infrastructure.compat;

import com.blogsystem.ai.application.AiChatPort;
import com.blogsystem.ai.application.EnabledAi;
import com.blogsystem.ai.application.ConversationMemoryPort;
import com.blogsystem.ai.application.KnowledgeIndexPort;
import com.blogsystem.ai.infrastructure.provider.SpringAiChatAdapter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import reactor.core.publisher.Flux;

@Component
@EnabledAi
@ConditionalOnProperty(name = "ai.client.mode", havingValue = "in-process", matchIfMissing = true)
public class InProcessAiAdapter implements AiChatPort, ConversationMemoryPort, KnowledgeIndexPort {
    private final SpringAiChatAdapter chatAdapter;
    private final ChatMemory chatMemory;

    public InProcessAiAdapter(SpringAiChatAdapter chatAdapter, ChatMemory chatMemory) {
        this.chatAdapter = chatAdapter;
        this.chatMemory = chatMemory;
    }

    @Override
    public Flux<String> stream(String principalId, String conversationId, String message) {
        return chatAdapter.chat(message, conversationId);
    }

    @Override
    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }

    @Override
    public int rebuild() {
        return chatAdapter.rebuildVectors();
    }
}
