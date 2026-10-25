package com.blogsystem.app.config.ai;

import com.blogsystem.application.ai.service.AiApplicationFacade;
import com.blogsystem.infrastructure.configuration.ai.AiVectorStoreSettings;
import com.blogsystem.infrastructure.gateway.ai.VectorStoreInitializer;
import com.blogsystem.infrastructure.gateway.ai.inprocess.InProcessAiAdapter;
import com.blogsystem.infrastructure.gateway.ai.springai.SpringAiChatAdapter;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import com.blogsystem.infrastructure.redis.ai.RedisChatMemory;
import com.blogsystem.infrastructure.redis.support.RedisFailurePolicy;
import com.blogsystem.trigger.job.ai.AiKnowledgeStartupListener;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.nio.file.Path;

/**
 * AI 配置 —— VectorStore 非阻塞初始化。
 * VectorStore bean 立即创建（尝试从持久化文件加载），
 * 文章向量化由 {@link VectorStoreInitializer} 在应用启动后异步完成。
 */
@Slf4j
@Configuration
@EnabledAi
@ConditionalOnProperty(name = "ai.client.mode", havingValue = "in-process", matchIfMissing = true)
public class AiConfig {

    /**
     * 向量存储持久化文件路径
     */
    public static final String VECTOR_STORE_FILE = "data/vector-store.json";

    @Bean
    AiVectorStoreSettings aiVectorStoreSettings() {
        return new AiVectorStoreSettings(Path.of(VECTOR_STORE_FILE));
    }

    @Bean
    VectorStoreInitializer vectorStoreInitializer(
            VectorStore store,
            EmbeddingModel model,
            ArticleMapper articles,
            AiVectorStoreSettings settings,
            @Value("${ai.knowledge.initialize-on-startup:true}") boolean initialize) {
        return new VectorStoreInitializer(store, model, articles, settings, initialize);
    }

    @Bean
    SpringAiChatAdapter springAiChatAdapter(
            ChatClient.Builder builder,
            ChatMemory memory,
            VectorStore vectors,
            StringRedisTemplate redis,
            RedisFailurePolicy failures,
            VectorStoreInitializer initializer) {
        return new SpringAiChatAdapter(builder, memory, vectors, redis, failures, initializer);
    }

    @Bean
    InProcessAiAdapter inProcessAiAdapter(SpringAiChatAdapter chat, ChatMemory memory) {
        return new InProcessAiAdapter(chat, memory);
    }

    @Bean
    AiKnowledgeStartupListener aiKnowledgeStartupListener(AiApplicationFacade ai) {
        return new AiKnowledgeStartupListener(ai);
    }

    @Bean
    public ChatMemory chatMemory(StringRedisTemplate redisTemplate) {
        return new RedisChatMemory(redisTemplate);
    }

    /**
     * VectorStore bean —— 立即创建并返回（非阻塞）。
     * 优先从持久化文件加载已有向量数据，文件不存在则创建空 store。
     * 文章向量化由 VectorStoreInitializer 异步完成，不阻塞应用启动。
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel, AiVectorStoreSettings settings) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File persistFile = settings.storePath().toFile();
        if (persistFile.exists()) {
            try {
                store.load(persistFile);
                log.info(
                        "VectorStore loaded from persisted file: {} ({} bytes)",
                        VECTOR_STORE_FILE,
                        persistFile.length());
            } catch (Exception e) {
                log.warn(
                        "Failed to load VectorStore from file {}, starting with empty store",
                        VECTOR_STORE_FILE,
                        e);
            }
        } else {
            log.info("No persisted VectorStore file found, starting with empty store");
        }
        return store;
    }
}
