package com.blogsystem.ai.infrastructure.provider;

import cn.dev33.satoken.stp.StpUtil;
import com.blogsystem.ai.infrastructure.configuration.VectorStoreInitializer;
import com.blogsystem.ai.application.EnabledAi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import com.blogsystem.shared.infrastructure.redis.RedisFailurePolicy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Component
@EnabledAi
@ConditionalOnProperty(name = "ai.client.mode", havingValue = "in-process", matchIfMissing = true)
public class SpringAiChatAdapter {

    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailurePolicy redisFailurePolicy;
    private final VectorStoreInitializer vectorStoreInitializer;

    private static final String SYSTEM_PROMPT = """
            你是 Javerry 博客的 AI 技术助手，名字叫「小J」。
            
            你的风格：热情、博学、略带程序员幽默感。用中文回答，技术术语保留英文。
            你的能力：回答编程、系统架构、数据库、前端、后端、AI 等技术问题。
            
            行为准则：
            1. 如果问题与技术无关，礼貌拒绝并引导回技术话题。
            2. 代码示例要完整可运行，标注语言。
            3. 回答结构清晰：先给结论，再展开解释，最后附代码示例。
            4. 不要编造 API 或版本号，不确定的请说明。
            5. 每次回答控制在 300-800 字。
            """;

    public SpringAiChatAdapter(ChatClient.Builder builder, ChatMemory chatMemory, VectorStore vectorStore,
                               StringRedisTemplate redisTemplate, RedisFailurePolicy redisFailurePolicy,
                               VectorStoreInitializer vectorStoreInitializer) {
        this.redisTemplate = redisTemplate;
        this.redisFailurePolicy = redisFailurePolicy;
        this.vectorStoreInitializer = vectorStoreInitializer;
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new QuestionAnswerAdvisor(vectorStore))
                .build();
    }

    public Flux<String> chat(String userMessage, String chatId) {
        // AI 频率限制：每用户每小时最多 50 次
        Long userId = StpUtil.getLoginIdAsLong();
        String rateKey = "rate:ai:user:" + userId;
        try {
            String count = redisTemplate.opsForValue().get(rateKey);
            if (count != null && Integer.parseInt(count) >= 50) {
                throw new IllegalArgumentException("AI 对话次数已达每小时上限，请稍后再试");
            }
        } catch (IllegalArgumentException e) {
            throw e; // Business rejection — re-throw
        } catch (Exception e) {
            redisFailurePolicy.onSecurityReadFailure("AI rate check", rateKey, e);
        }
        // Record rate limit (best-effort)
        try {
            redisTemplate.opsForValue().increment(rateKey);
            redisTemplate.expire(rateKey, Duration.ofHours(1));
        } catch (Exception e) {
            redisFailurePolicy.onSecurityWriteFailure("AI rate set", rateKey, e);
        }

        return chatClient
                .prompt()
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    public void clearMemory(String chatId) {
        // 委托 Controller 中的 ChatMemory 直接操作（避免在 Service 层维护 ChatMemory 引用）
    }

    /**
     * 重建向量存储：清空现有向量并重新加载所有已发布文章。
     * 管理端调用，加载期间会跳过重复请求。
     *
     * @return 加载的文档块数量，-1 表示已有加载任务在进行中
     */
    public int rebuildVectors() {
        return vectorStoreInitializer.rebuild();
    }
}
