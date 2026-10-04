package com.blogsystem.ai.domain.port;

import com.blogsystem.ai.domain.conversation.ConversationId;

import java.util.concurrent.Flow;

public interface ChatModel {
    Flow.Publisher<String> stream(ConversationId conversationId, String message);
}
