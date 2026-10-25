package com.blogsystem.infrastructure.gateway.ai.http;

import com.blogsystem.domain.ai.gateway.AiChatPort;
import com.blogsystem.domain.ai.gateway.ConversationMemoryPort;
import com.blogsystem.domain.ai.gateway.KnowledgeIndexPort;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.adapter.JdkFlowAdapter;

import java.util.Map;
import java.util.concurrent.Flow;

public class HttpBlogAiAdapter implements AiChatPort, ConversationMemoryPort, KnowledgeIndexPort {
    private final WebClient client;

    public HttpBlogAiAdapter(WebClient.Builder builder, String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Flow.Publisher<String> stream(
            String principalId, String conversationId, String message) {
        return JdkFlowAdapter.publisherToFlowPublisher(
                client.post()
                        .uri("/internal/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .bodyValue(
                                Map.of(
                                        "principalId",
                                        principalId,
                                        "conversationId",
                                        conversationId,
                                        "message",
                                        message))
                        .retrieve()
                        .bodyToFlux(String.class));
    }

    @Override
    public void clear(String conversationId) {
        client.delete()
                .uri("/internal/v1/ai/conversations/{id}", conversationId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    @Override
    public int rebuild() {
        client.post()
                .uri("/internal/v1/knowledge/indexes:rebuild")
                .retrieve()
                .toBodilessEntity()
                .block();
        return 0;
    }

    @Override
    public void initializeOnStartup() {
        // The remote AI process owns its startup lifecycle; never trigger rebuild here.
    }
}
