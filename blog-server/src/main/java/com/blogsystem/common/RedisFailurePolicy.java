package com.blogsystem.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Profile-aware Redis failure handling policy.
 * <p>
 * Cache scenarios: always degrade gracefully, log WARN.
 * Security scenarios: in dev — log WARN and allow through; in prod — throw error to refuse the request.
 */
@Slf4j
@Component
public class RedisFailurePolicy {

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private boolean isDev() {
        return "dev".equals(activeProfile);
    }

    /**
     * Handle a Redis failure in a cache (degradable) scenario.
     * Logs a warning and the caller should continue without cache.
     */
    public void onCacheFailure(String operation, String key, Exception e) {
        log.warn("Redis cache operation failed (degraded): operation={} key={}", operation, key, e);
    }

    /**
     * Handle a Redis failure in a security read scenario (rate limit check, token check).
     * <ul>
     *   <li>Dev profile: logs WARN and returns (caller allows the request through)</li>
     *   <li>Prod profile: logs ERROR and throws IllegalArgumentException</li>
     * </ul>
     */
    public void onSecurityReadFailure(String operation, String key, Exception e) {
        if (isDev()) {
            log.warn("Redis security read failed (dev: allowing through): operation={} key={}", operation, key, e);
        } else {
            log.error("Redis security read failed (prod: rejecting request): operation={} key={}", operation, key, e);
            throw new IllegalArgumentException("服务繁忙，请稍后再试");
        }
    }

    /**
     * Handle a Redis failure in a security write scenario (setting rate limit counters).
     * Always logs a warning and continues — the rate limit simply won't be recorded for this window.
     */
    public void onSecurityWriteFailure(String operation, String key, Exception e) {
        log.warn("Redis security write failed (continuing without rate record): operation={} key={}", operation, key, e);
    }
}
