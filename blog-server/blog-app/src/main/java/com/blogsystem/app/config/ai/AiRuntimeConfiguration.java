package com.blogsystem.app.config.ai;

import com.blogsystem.application.ai.service.AiApplicationFacade;
import com.blogsystem.domain.ai.gateway.AiChatPort;
import com.blogsystem.domain.ai.gateway.ConversationMemoryPort;
import com.blogsystem.domain.ai.gateway.KnowledgeIndexPort;
import com.blogsystem.infrastructure.gateway.ai.http.HttpBlogAiAdapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** The only composition root for AI use cases and remote adapters. */
@Configuration
@EnabledAi
public class AiRuntimeConfiguration {
    @Bean
    AiApplicationFacade aiApplicationFacade(
            AiChatPort chat, ConversationMemoryPort memory, KnowledgeIndexPort indexes) {
        return new AiApplicationFacade(chat, memory, indexes);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.client.mode", havingValue = "http")
    HttpBlogAiAdapter httpBlogAiAdapter(
            WebClient.Builder builder,
            @Value("${ai.client.base-url:http://localhost:8090}") String baseUrl) {
        return new HttpBlogAiAdapter(builder, baseUrl);
    }
}
