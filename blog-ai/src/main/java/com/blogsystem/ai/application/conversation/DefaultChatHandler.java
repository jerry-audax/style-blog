package com.blogsystem.ai.application.conversation;

import com.blogsystem.ai.domain.conversation.ConversationId;
import com.blogsystem.ai.domain.port.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.adapter.JdkFlowAdapter;

@Service
public class DefaultChatHandler implements ChatHandler {
    private final ChatModel chatModel;

    public DefaultChatHandler(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Flux<String> stream(ChatCommand command) {
        return JdkFlowAdapter.flowPublisherToFlux(chatModel.stream(
                new ConversationId(command.principalId(), command.conversationId()), command.message()));
    }
}
