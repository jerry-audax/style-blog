package com.blogsystem.infrastructure.security.identity;

import static com.blogsystem.domain.identity.model.OwnerAccessException.Reason.RATE_LIMITED;
import static com.blogsystem.domain.identity.model.OwnerAccessException.Reason.UNAVAILABLE;

import com.blogsystem.domain.identity.gateway.LoginProtection;
import com.blogsystem.domain.identity.model.OwnerAccessException;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisLoginProtection implements LoginProtection {
    private final StringRedisTemplate redis;

    public RedisLoginProtection(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static final DefaultRedisScript<Long> ATTEMPT =
            new DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 then"
                            + " redis.call('EXPIRE',KEYS[1],60) end; return n",
                    Long.class);

    @Override
    public void checkAttempt(String requestIp) {
        final Long count;
        try {
            count = redis.execute(ATTEMPT, List.of("rate:owner-login:ip:" + requestIp));
        } catch (RuntimeException unavailable) {
            throw new OwnerAccessException(UNAVAILABLE);
        }
        if (count == null) throw new OwnerAccessException(UNAVAILABLE);
        if (count > 5) throw new OwnerAccessException(RATE_LIMITED);
    }
}
