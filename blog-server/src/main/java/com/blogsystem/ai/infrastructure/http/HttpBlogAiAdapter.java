package com.blogsystem.ai.infrastructure.http;

import com.blogsystem.ai.application.AiChatPort;
import com.blogsystem.ai.application.EnabledAi;
import com.blogsystem.ai.application.ConversationMemoryPort;
import com.blogsystem.ai.application.KnowledgeIndexPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;

@Component
@EnabledAi
@ConditionalOnProperty(name = "ai.client.mode", havingValue = "http")
public class HttpBlogAiAdapter implements AiChatPort, ConversationMemoryPort, KnowledgeIndexPort {
    private final WebClient client;

    public HttpBlogAiAdapter(WebClient.Builder builder,
                             @Value("${ai.client.base-url:http://localhost:8090}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Flux<String> stream(String principalId, String conversationId, String message) {
        return client.post().uri("/internal/v1/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("principalId", principalId, "conversationId", conversationId, "message", message))
                .retrieve().bodyToFlux(String.class);
    }

    @Override
    public void clear(String conversationId) {
        client.delete().uri("/internal/v1/ai/conversations/{id}", conversationId).retrieve().toBodilessEntity().block();
    }

    @Override
    public int rebuild() {
        client.post().uri("/internal/v1/knowledge/indexes:rebuild").retrieve().toBodilessEntity().block();
        return 0;
    }
}
