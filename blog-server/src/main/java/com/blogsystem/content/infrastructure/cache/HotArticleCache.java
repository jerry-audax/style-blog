package com.blogsystem.content.infrastructure.cache;

import com.blogsystem.shared.infrastructure.redis.RedisFailurePolicy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class HotArticleCache {
    private final StringRedisTemplate redis;
    private final RedisFailurePolicy failures;

    public HotArticleCache(StringRedisTemplate redis, RedisFailurePolicy failures) {
        this.redis = redis;
        this.failures = failures;
    }

    public void evictAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict();
                }
            });
        } else evict();
    }

    private void evict() {
        for (String key : new String[]{"cache:hotArticles", "cache:hotByLikes"}) {
            try {
                redis.delete(key);
            } catch (Exception error) {
                failures.onCacheFailure("evict article cache", key, error);
            }
        }
    }
}
