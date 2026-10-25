package com.blogsystem.infrastructure.redis.interaction;

import com.blogsystem.infrastructure.redis.interaction.RedisCommentRateLimit;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.mockito.Mockito;

import com.blogsystem.types.exception.DomainException;
import com.blogsystem.infrastructure.redis.support.RedisFailurePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisCommentRateLimitTest {
    @SuppressWarnings("unchecked")
    @Test
    void limiterChecksAndRecordsInOneRedisScriptCall() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(1L);
        new RedisCommentRateLimit(redis, mock(RedisFailurePolicy.class)).requireAllowed(42);
        verify(redis).execute(argThat((RedisScript<Long> script) -> script.getScriptAsString().contains("INCR")
                && script.getScriptAsString().contains("EXPIRE")), eq(java.util.List.of("rate:comment:user:42")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void exhaustedWindowIsBusinessRejection() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(0L);
        assertThatThrownBy(() -> new RedisCommentRateLimit(redis, mock(RedisFailurePolicy.class)).requireAllowed(42))
                .isInstanceOf(DomainException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void productionRedisFailureRemainsFailClosed() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new IllegalStateException("fake outage"));
        var policy = new RedisFailurePolicy();
        ReflectionTestUtils.setField(policy, "activeProfile", "prod");
        assertThatThrownBy(() -> new RedisCommentRateLimit(redis, policy).requireAllowed(42)).isInstanceOf(IllegalArgumentException.class);
    }
}
