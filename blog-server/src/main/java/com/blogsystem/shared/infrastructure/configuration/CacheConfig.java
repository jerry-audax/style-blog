package com.blogsystem.shared.infrastructure.configuration;

import org.springframework.context.annotation.Configuration;

/**
 * Redis 配置：连接信息在 application-dev.yml 的 spring.data.redis 段。
 * spring-boot-starter-data-redis 自动配置 StringRedisTemplate 和 RedisConnectionFactory。
 */
@Configuration
public class CacheConfig {
}
