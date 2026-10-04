package com.blogsystem.ai.application;

import reactor.core.publisher.Flux;

public interface AiChatPort {
    Flux<String> stream(String principalId, String conversationId, String message);
}
