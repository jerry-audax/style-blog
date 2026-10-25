package com.blogsystem.infrastructure.security.identity;

import static com.blogsystem.domain.identity.model.valobj.OwnerTokenVerificationException.Reason.REVOKED;
import static com.blogsystem.domain.identity.model.valobj.OwnerTokenVerificationException.Reason.UNAVAILABLE;

import com.blogsystem.domain.identity.gateway.OwnerTokenVerifier;
import com.blogsystem.domain.identity.model.valobj.OwnerTokenVerificationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** Preserves the existing profile-aware token lookup policy without servlet/container access. */
@Slf4j
public final class RedisOwnerTokenVerifier implements OwnerTokenVerifier {
    private static final String REDIS_KEY_PREFIX = "Authorization:login:token:";
    private final Supplier<StringRedisTemplate> redis;
    private final Supplier<Environment> environments;

    public RedisOwnerTokenVerifier(
            Supplier<StringRedisTemplate> redis, Supplier<Environment> environments) {
        this.redis = Objects.requireNonNull(redis);
        this.environments = Objects.requireNonNull(environments);
    }

    @Override
    public void verify(String token) {
        if (token == null || token.isEmpty()) return;
        try {
            if (Boolean.FALSE.equals(redis.get().hasKey(REDIS_KEY_PREFIX + token))) {
                throw new OwnerTokenVerificationException(REVOKED);
            }
        } catch (OwnerTokenVerificationException failure) {
            throw failure;
        } catch (Exception failure) {
            handleLookupFailure(failure);
        }
    }

    private void handleLookupFailure(Exception failure) {
        try {
            for (String profile : environments.get().getActiveProfiles()) {
                if ("dev".equals(profile)) {
                    log.warn("Redis 令牌校验异常（dev: 降级放行）", failure);
                    return;
                }
            }
            log.error("Redis 令牌校验异常（prod: 拒绝请求）", failure);
            throw new OwnerTokenVerificationException(UNAVAILABLE);
        } catch (OwnerTokenVerificationException rejected) {
            throw rejected;
        } catch (Exception unavailableEnvironment) {
            // Existing policy gap: do not silently tighten authorization during a structural
            // migration.
            log.warn("无法获取运行环境配置，降级放行", unavailableEnvironment);
        }
    }
}
