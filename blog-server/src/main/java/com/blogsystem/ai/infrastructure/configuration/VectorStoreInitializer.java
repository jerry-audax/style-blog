package com.blogsystem.ai.infrastructure.configuration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blogsystem.content.infrastructure.persistence.Article;
import com.blogsystem.content.infrastructure.persistence.ArticleMapper;
import com.blogsystem.ai.application.EnabledAi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 向量存储初始化器 —— 在应用启动后异步加载文章向量，不阻塞启动流程。
 * 同时提供重建向量存储的方法供管理端调用。
 */
@Slf4j
@Component
@EnabledAi
@ConditionalOnProperty(name = "ai.client.mode", havingValue = "in-process", matchIfMissing = true)
@RequiredArgsConstructor
public class VectorStoreInitializer {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final ArticleMapper articleMapper;

    @Value("${ai.knowledge.initialize-on-startup:true}")
    private boolean initializeOnStartup = true;

    private final AtomicBoolean loading = new AtomicBoolean(false);

    /**
     * 应用启动完成后异步加载文章到向量存储。
     * 失败不影响应用正常运行，仅记录错误日志。
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void loadArticlesOnStartup() {
        if (!initializeOnStartup) {
            log.info("VectorStore startup initialization disabled for this run");
            return;
        }
        log.info("VectorStore async initialization started...");
        doLoadArticles();
        log.info("VectorStore async initialization completed");
    }

    /**
     * 重建向量存储：清空现有向量，重新加载所有已发布文章。
     * 加载期间会跳过该操作。
     *
     * @return 加载的文档块数量
     */
    public int rebuild() {
        if (!loading.compareAndSet(false, true)) {
            log.warn("VectorStore rebuild already in progress, skipping");
            return -1;
        }
        try {
            log.info("VectorStore rebuild started...");
            clearStore();
            int count = doLoadArticles();
            log.info("VectorStore rebuild completed — {} chunks loaded", count);
            return count;
        } finally {
            loading.set(false);
        }
    }

    /**
     * 执行文章加载逻辑：查询所有已发布文章，分块后添加到向量存储并持久化。
     *
     * @return 添加的文档块数量，如果发生异常返回 -1
     */
    private int doLoadArticles() {
        try {
            List<Article> articles = articleMapper.selectList(
                    new LambdaQueryWrapper<Article>()
                            .eq(Article::getDeleted, 0)
                            .eq(Article::getStatus, 1));
            log.info("VectorStore loading {} published articles", articles.size());

            List<Document> allChunks = new ArrayList<>();
            int skippedShort = 0;
            for (Article a : articles) {
                if (a.getContentMd() == null || a.getContentMd().length() < 100) {
                    skippedShort++;
                    continue;
                }
                Document doc = new Document(a.getContentMd(),
                        Map.of("articleId", String.valueOf(a.getId()),
                                "title", a.getTitle() != null ? a.getTitle() : ""));
                TokenTextSplitter splitter = new TokenTextSplitter(200, 200, 50, 1, true);
                allChunks.addAll(splitter.apply(List.of(doc)));
            }

            if (skippedShort > 0) {
                log.info("Skipped {} articles with content < 100 chars", skippedShort);
            }

            if (!allChunks.isEmpty()) {
                vectorStore.add(allChunks);
                log.info("VectorStore loaded {} document chunks from {} articles",
                        allChunks.size(), articles.size() - skippedShort);
            } else {
                log.warn("No document chunks generated — vector store remains empty");
            }

            // 持久化到文件
            persistToFile();

            return allChunks.size();
        } catch (Exception e) {
            log.error("VectorStore article loading failed — vector store may be empty", e);
            return -1;
        }
    }

    /**
     * 清空 SimpleVectorStore 内部存储（通过反射访问 ConcurrentHashMap）。
     */
    private void clearStore() {
        if (!(vectorStore instanceof SimpleVectorStore svs)) {
            log.warn("VectorStore is not SimpleVectorStore, cannot clear");
            return;
        }
        try {
            Field field = SimpleVectorStore.class.getDeclaredField("store");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Document> map = (Map<String, Document>) field.get(svs);
            int oldSize = map.size();
            map.clear();
            log.info("VectorStore cleared (removed {} documents)", oldSize);
        } catch (NoSuchFieldException e) {
            log.warn("Could not find internal store field in SimpleVectorStore — field name may have changed", e);
        } catch (Exception e) {
            log.error("Failed to clear VectorStore", e);
        }
    }

    /**
     * 将当前向量存储持久化到文件。
     * 持久化失败仅记录警告，不影响服务运行。
     */
    private void persistToFile() {
        if (!(vectorStore instanceof SimpleVectorStore svs)) {
            return;
        }
        try {
            File dir = new File("data");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(AiConfig.VECTOR_STORE_FILE);
            svs.save(file);
            log.info("VectorStore persisted to {}", AiConfig.VECTOR_STORE_FILE);
        } catch (Exception e) {
            log.warn("Failed to persist VectorStore to file — vector data will be lost on restart", e);
        }
    }
}
