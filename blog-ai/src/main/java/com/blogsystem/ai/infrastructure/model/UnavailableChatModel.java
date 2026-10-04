package com.blogsystem.ai.infrastructure.model;

import com.blogsystem.ai.domain.conversation.ConversationId;
import com.blogsystem.ai.domain.port.ChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.adapter.JdkFlowAdapter;

import java.util.concurrent.Flow;

@Component
@Profile("!dashscope")
public class UnavailableChatModel implements ChatModel {
    @Override
    public Flow.Publisher<String> stream(ConversationId conversationId, String message) {
        return JdkFlowAdapter.publisherToFlowPublisher(Flux.error(new IllegalStateException("AI model adapter is not configured")));
    }
}
