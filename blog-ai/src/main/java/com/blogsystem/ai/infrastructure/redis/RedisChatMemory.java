package com.blogsystem.ai.infrastructure.redis;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("dashscope")
public class RedisChatMemory implements ChatMemory {
    private static final String PREFIX = "ai:chat:mem:";
    private static final int MAX_SIZE = 40;
    private final StringRedisTemplate redis;

    public RedisChatMemory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        String key = PREFIX + conversationId;
        for (Message message : messages) {
            String type = message instanceof UserMessage ? "U:" : "A:";
            redis.opsForList().rightPush(key, type + message.getText());
        }
        Long size = redis.opsForList().size(key);
        if (size != null && size > MAX_SIZE) redis.opsForList().trim(key, size - MAX_SIZE, -1);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<String> raw = redis.opsForList().range(PREFIX + conversationId, 0, -1);
        if (raw == null) return List.of();
        List<Message> result = new ArrayList<>();
        for (String value : raw) {
            if (value.startsWith("U:")) result.add(new UserMessage(value.substring(2)));
            else if (value.startsWith("A:")) result.add(new AssistantMessage(value.substring(2)));
        }
        return result;
    }

    @Override
    public void clear(String conversationId) {
        redis.delete(PREFIX + conversationId);
    }
}
