package com.blogsystem.ai.infrastructure.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed chat memory for AI conversations.
 * CACHE scenario — all operations degrade gracefully on Redis failure.
 */
@Slf4j
public class RedisChatMemory implements ChatMemory {

    private final StringRedisTemplate redis;
    private static final String PREFIX = "chat:mem:";
    private static final int MAX_SIZE = 40;

    public RedisChatMemory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        String key = PREFIX + conversationId;
        try {
            for (Message msg : messages) {
                String type = msg instanceof UserMessage ? "U:" : "A:";
                redis.opsForList().rightPush(key, type + msg.getText());
            }
            Long size = redis.opsForList().size(key);
            if (size != null && size > MAX_SIZE) {
                redis.opsForList().trim(key, size - MAX_SIZE, -1);
            }
        } catch (Exception e) {
            log.warn("Redis chat memory add failed (degraded): operation=add key={}", key, e);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        String key = PREFIX + conversationId;
        try {
            List<String> raw = redis.opsForList().range(key, 0, -1);
            if (raw == null || raw.isEmpty()) return List.of();
            List<Message> result = new ArrayList<>();
            for (String s : raw) {
                if (s.startsWith("U:")) {
                    result.add(new UserMessage(s.substring(2)));
                } else if (s.startsWith("A:")) {
                    result.add(new AssistantMessage(s.substring(2)));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Redis chat memory get failed (degraded): operation=get key={}", key, e);
            return List.of(); // Return empty — AI will respond without history
        }
    }

    @Override
    public void clear(String conversationId) {
        String key = PREFIX + conversationId;
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis chat memory clear failed (degraded): operation=clear key={}", key, e);
        }
    }
}
