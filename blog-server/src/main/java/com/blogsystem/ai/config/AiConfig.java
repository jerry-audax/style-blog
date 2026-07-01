package com.blogsystem.ai.config;

import com.blogsystem.ai.memory.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;

/**
 * AI 配置 —— VectorStore 非阻塞初始化。
 * VectorStore bean 立即创建（尝试从持久化文件加载），
 * 文章向量化由 {@link VectorStoreInitializer} 在应用启动后异步完成。
 */
@Slf4j
@Configuration
public class AiConfig {

    /** 向量存储持久化文件路径 */
    public static final String VECTOR_STORE_FILE = "data/vector-store.json";

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
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File persistFile = new File(VECTOR_STORE_FILE);
        if (persistFile.exists()) {
            try {
                store.load(persistFile);
                log.info("VectorStore loaded from persisted file: {} ({} bytes)",
                        VECTOR_STORE_FILE, persistFile.length());
            } catch (Exception e) {
                log.warn("Failed to load VectorStore from file {}, starting with empty store", VECTOR_STORE_FILE, e);
            }
        } else {
            log.info("No persisted VectorStore file found, starting with empty store");
        }
        return store;
    }
}
