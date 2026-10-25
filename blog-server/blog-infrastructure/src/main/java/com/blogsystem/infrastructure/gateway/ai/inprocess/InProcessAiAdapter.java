package com.blogsystem.infrastructure.gateway.ai.inprocess;

import com.blogsystem.domain.ai.gateway.AiChatPort;
import com.blogsystem.domain.ai.gateway.ConversationMemoryPort;
import com.blogsystem.domain.ai.gateway.KnowledgeIndexPort;
import com.blogsystem.infrastructure.gateway.ai.springai.SpringAiChatAdapter;

import org.springframework.ai.chat.memory.ChatMemory;

import reactor.adapter.JdkFlowAdapter;

import java.util.concurrent.Flow;

public class InProcessAiAdapter implements AiChatPort, ConversationMemoryPort, KnowledgeIndexPort {
    private final SpringAiChatAdapter chatAdapter;
    private final ChatMemory chatMemory;

    public InProcessAiAdapter(SpringAiChatAdapter chatAdapter, ChatMemory chatMemory) {
        this.chatAdapter = chatAdapter;
        this.chatMemory = chatMemory;
    }

    @Override
    public Flow.Publisher<String> stream(
            String principalId, String conversationId, String message) {
        return JdkFlowAdapter.publisherToFlowPublisher(chatAdapter.chat(message, conversationId));
    }

    @Override
    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }

    @Override
    public int rebuild() {
        return chatAdapter.rebuildVectors();
    }

    @Override
    public void initializeOnStartup() {
        chatAdapter.initializeKnowledgeOnStartup();
    }
}
