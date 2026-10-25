package com.blogsystem.app.config.identity;

import com.blogsystem.domain.identity.gateway.OwnerTokenVerifier;
import com.blogsystem.domain.identity.model.SingleOwnerPolicy;
import com.blogsystem.infrastructure.security.identity.RedisOwnerTokenVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class OwnerIdentityConfiguration {
    @Bean
    OwnerTokenVerifier ownerTokenVerifier(
            ObjectProvider<StringRedisTemplate> redis, ObjectProvider<Environment> environment) {
        return new RedisOwnerTokenVerifier(redis::getObject, environment::getObject);
    }

    @Bean
    SingleOwnerPolicy singleOwnerPolicy(@Value("${blog.owner.phone}") String phone) {
        return new SingleOwnerPolicy(phone);
    }
}
