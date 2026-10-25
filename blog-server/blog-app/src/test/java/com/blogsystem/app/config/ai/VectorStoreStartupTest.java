package com.blogsystem.app.config.ai;

import com.blogsystem.infrastructure.configuration.ai.AiVectorStoreSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import com.blogsystem.infrastructure.gateway.ai.VectorStoreInitializer;

import org.mockito.Mockito;

import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class VectorStoreStartupTest {
    @TempDir Path temporary;
    @Test
    void disabledStartupDoesNotReadArticlesOrCallTheCloudModel() {
        var store = mock(VectorStore.class);
        var model = mock(EmbeddingModel.class);
        var articles = mock(ArticleMapper.class);
        var initializer = new VectorStoreInitializer(store, model, articles,
                new AiVectorStoreSettings(temporary.resolve("vectors.json")), false);
        initializer.loadArticlesOnStartup();
        verifyNoInteractions(store, model, articles);
    }

    @Test
    void startupLoadsAtMostOnceAndNeverClearsExistingIndex() {
        var store = mock(org.springframework.ai.vectorstore.SimpleVectorStore.class);
        var retained = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        retained.put("existing-document", new Object());
        ReflectionTestUtils.setField(store, "store", retained);
        var model = mock(EmbeddingModel.class);
        var articles = mock(ArticleMapper.class);
        when(articles.selectList(any())).thenReturn(java.util.List.of());
        var initializer = new VectorStoreInitializer(store, model, articles,
                new AiVectorStoreSettings(temporary.resolve("vectors.json")), true);
        initializer.loadArticlesOnStartup();
        initializer.loadArticlesOnStartup();
        assertTrue(retained.containsKey("existing-document"));
        verify(articles, times(1)).selectList(any());
        verifyNoInteractions(model);
        verify(store, never()).add(anyList());
    }

    @Test
    void startupTriggerRequestsInitializationRatherThanRebuild() {
        var initialized = new java.util.concurrent.atomic.AtomicInteger();
        var rebuilt = new java.util.concurrent.atomic.AtomicInteger();
        var index = new com.blogsystem.domain.ai.gateway.KnowledgeIndexPort() {
            public void initializeOnStartup() { initialized.incrementAndGet(); }
            public int rebuild() { return rebuilt.incrementAndGet(); }
        };
        var ai = new com.blogsystem.application.ai.service.AiApplicationFacade((p, c, m) -> subscriber -> {}, c -> {}, index);
        new com.blogsystem.trigger.job.ai.AiKnowledgeStartupListener(ai).onApplicationReady();
        assertEquals(1, initialized.get());
        assertEquals(0, rebuilt.get());
    }
}
