package com.blogsystem.domain.ai.gateway;

import java.util.concurrent.Flow;

public interface AiChatPort {
    Flow.Publisher<String> stream(String principalId, String conversationId, String message);
}
