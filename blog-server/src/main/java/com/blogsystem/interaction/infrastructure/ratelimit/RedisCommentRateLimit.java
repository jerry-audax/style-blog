package com.blogsystem.interaction.infrastructure.ratelimit;

import com.blogsystem.interaction.domain.port.CommentRateLimit;
import com.blogsystem.shared.domain.DomainException;
import com.blogsystem.shared.infrastructure.redis.RedisFailurePolicy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisCommentRateLimit implements CommentRateLimit {
    private static final DefaultRedisScript<Long> CHECK_AND_RECORD = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= 3 then return 0 end
            local next = redis.call('INCR', KEYS[1])
            if next == 1 then redis.call('EXPIRE', KEYS[1], 60) end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final RedisFailurePolicy failures;

    public RedisCommentRateLimit(StringRedisTemplate redis, RedisFailurePolicy failures) {
        this.redis = redis;
        this.failures = failures;
    }

    @Override
    public void requireAllowed(long userId) {
        String key = "rate:comment:user:" + userId;
        Long allowed;
        try {
            allowed = redis.execute(CHECK_AND_RECORD, List.of(key));
        } catch (Exception error) {
            failures.onSecurityReadFailure("comment rate check", key, error);
            return;
        }
        if (!Long.valueOf(1).equals(allowed)) throw new DomainException("评论过于频繁，请稍后再试");
    }
}
