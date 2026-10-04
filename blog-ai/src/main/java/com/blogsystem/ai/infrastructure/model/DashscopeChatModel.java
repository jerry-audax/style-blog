package com.blogsystem.ai.infrastructure.model;

import com.blogsystem.ai.domain.conversation.ConversationId;
import com.blogsystem.ai.domain.port.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.adapter.JdkFlowAdapter;

import java.util.concurrent.Flow;

@Component
@Profile("dashscope")
public class DashscopeChatModel implements ChatModel {
    private static final String SYSTEM_PROMPT = "你是博客技术助手，请用中文回答技术问题，结论先行，内容准确。";
    private final ChatClient client;

    public DashscopeChatModel(ChatClient.Builder builder, ChatMemory memory, VectorStore vectorStore) {
        this.client = builder.defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build(), new QuestionAnswerAdvisor(vectorStore))
                .build();
    }

    @Override
    public Flow.Publisher<String> stream(ConversationId conversationId, String message) {
        return JdkFlowAdapter.publisherToFlowPublisher(client.prompt().user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId.value()))
                .stream().content());
    }
}
