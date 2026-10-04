package com.blogsystem.ai.infrastructure.configuration;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.File;

@Configuration
@Profile("dashscope")
public class VectorStoreConfiguration {
    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = new File("data/vector-store.json");
        if (file.exists()) {
            try {
                store.load(file);
            } catch (Exception ignored) {
            }
        }
        return store;
    }
}
