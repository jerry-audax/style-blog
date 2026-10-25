package com.blogsystem.infrastructure.redis.content;

import com.blogsystem.infrastructure.redis.content.HotArticleCache;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.infrastructure.redis.support.RedisFailurePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HotArticleCacheTest {
    private final InMemoryRedis redis = new InMemoryRedis();
    private final HotArticleCache cache = new HotArticleCache(redis, new RedisFailurePolicy());

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void removesBothRankingsOutsideTransactionButPreservesUnrelatedKeys() {
        cache.evictAfterCommit();
        assertEquals(Set.of("unrelated"), redis.keys);
    }

    @Test
    void defersRankingEvictionUntilCommit() {
        beginTransaction();
        cache.evictAfterCommit();
        assertEquals(Set.of("cache:hotArticles", "cache:hotByLikes", "unrelated"), redis.keys);

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        assertEquals(Set.of("unrelated"), redis.keys);
    }

    @Test
    void rolledBackArticleChangesDoNotEvictRankings() {
        beginTransaction();
        cache.evictAfterCommit();
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        assertEquals(Set.of("cache:hotArticles", "cache:hotByLikes", "unrelated"), redis.keys);
    }

    @Test
    void cacheFailureDoesNotFailArticleOperationOrPreventOtherRankingEviction() {
        redis.unavailableKey = "cache:hotArticles";
        assertDoesNotThrow(cache::evictAfterCommit);
        assertEquals(Set.of("cache:hotArticles", "unrelated"), redis.keys);
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    /** Only the external Redis delete boundary is replaced; cache and transaction callbacks stay real. */
    private static class InMemoryRedis extends StringRedisTemplate {
        private final Set<String> keys = new HashSet<>(Set.of("cache:hotArticles", "cache:hotByLikes", "unrelated"));
        private String unavailableKey;

        @Override
        public Boolean delete(String key) {
            if (key.equals(unavailableKey)) throw new IllegalStateException("simulated Redis outage");
            return keys.remove(key);
        }
    }
}
