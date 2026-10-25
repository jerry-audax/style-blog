package com.blogsystem.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionProfileConfigurationTest {
    private ApplicationContextRunner production() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "DB_URL=jdbc:postgresql://database-fixture:5432/blog_system",
                        "DB_USERNAME=synthetic_owner", "DB_PASSWORD=synthetic_database_password",
                        "JWT_SECRET=synthetic-production-test-jwt-secret-not-a-real-key",
                        "PUBLICATION_API_TOKEN=synthetic-production-publication-key-not-real",
                        "BLOG_OWNER_PHONE=19140838906");
    }

    @Test void productionResolvesComposeSettingsWithoutLoadingDevelopmentProfile() {
        production().run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly("prod");
            assertThat(env.getProperty("server.port")).isEqualTo("8080");
            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://database-fixture:5432/blog_system");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("synthetic_owner");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("synthetic_database_password");
            assertThat(env.getProperty("sa-token.jwt-secret-key"))
                    .isEqualTo("synthetic-production-test-jwt-secret-not-a-real-key");
            assertThat(env.getProperty("sa-token.token-name")).isEqualTo("Authorization");
            assertThat(env.getProperty("blog.owner.phone")).isEqualTo("19140838906");
            assertThat(env.getProperty("spring.sql.init.mode")).isEqualTo("never");
            assertThat(env.getProperty("media.catalog.storage")).isEqualTo("postgres");
            assertThat(env.getProperty("media.catalog.uploads.enabled")).isEqualTo("false");
            assertThat(env.getProperty("ai.enabled")).isEqualTo("false");
            assertThat(env.getProperty("mybatis-plus.global-config.db-config.logic-delete-field")).isEqualTo("deleted");
        });
    }

    @Test void productionHonorsExplicitMediaAndRedisEnvironmentSettings() {
        production().withPropertyValues("MEDIA_CATALOG_STORAGE=legacy-json", "REDIS_HOST=redis-fixture", "REDIS_PORT=6382",
                "IMGBED_BASE_URL=https://images.example", "IMGBED_UPLOAD_CHANNEL=telegram", "IMGBED_CHANNEL_NAME=fixture-channel")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var env = context.getEnvironment();
                    assertThat(env.getProperty("media.catalog.storage")).isEqualTo("legacy-json");
                    assertThat(env.getProperty("spring.data.redis.host")).isEqualTo("redis-fixture");
                    assertThat(env.getProperty("spring.data.redis.port")).isEqualTo("6382");
                    assertThat(env.getProperty("imgbed.base-url")).isEqualTo("https://images.example");
                    assertThat(env.getProperty("imgbed.channel-name")).isEqualTo("fixture-channel");
                });
    }
}
