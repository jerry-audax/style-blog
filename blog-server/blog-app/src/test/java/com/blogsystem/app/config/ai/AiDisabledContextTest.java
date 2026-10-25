package com.blogsystem.app.config.ai;

import com.blogsystem.app.config.ai.AiConfig;
import com.blogsystem.infrastructure.gateway.ai.VectorStoreInitializer;

import org.assertj.core.api.Assertions;
import org.springframework.ai.chat.client.ChatClient.Builder;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;

import com.blogsystem.application.ai.service.AiApplicationFacade;
import com.blogsystem.infrastructure.gateway.ai.inprocess.InProcessAiAdapter;
import com.blogsystem.infrastructure.gateway.ai.http.HttpBlogAiAdapter;
import com.blogsystem.infrastructure.gateway.ai.springai.SpringAiChatAdapter;
import com.blogsystem.trigger.http.ai.AiController;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real component scan and Boot auto-configuration, no provider keys or paid requests.
 */
class AiDisabledContextTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiSlice.class);

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
    @org.springframework.context.annotation.Import({AiRuntimeConfiguration.class, AiConfig.class})
    @ComponentScan(basePackageClasses = AiController.class)
    static class AiSlice {
    }

    @Test
    void factoriesFilterLoadsAndAiDisabledCreatesNoProvider() throws Exception {
        var entries = getClass().getClassLoader().getResources("META-INF/spring.factories");
        int found = 0;
        while (entries.hasMoreElements()) {
            var properties = new java.util.Properties();
            try (var input = entries.nextElement().openStream()) { properties.load(input); }
            for (String type : properties.getProperty("org.springframework.boot.autoconfigure.AutoConfigurationImportFilter", "").split(",")) {
                if (!type.trim().startsWith("com.blogsystem.")) continue;
                org.junit.jupiter.api.Assertions.assertInstanceOf(
                        org.springframework.boot.autoconfigure.AutoConfigurationImportFilter.class,
                        Class.forName(type.trim()).getDeclaredConstructor().newInstance());
                found++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, found);
        runner.withPropertyValues("ai.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertDisabled(context);
        });
    }

    @Test
    void defaultBootStartsWithoutAiKeysAndRegistersNoAiComponentsOrModels() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertDisabled(context);
        });
    }

    @Test
    void explicitOffWinsEvenIfTheHttpClientModeIsConfigured() {
        runner.withPropertyValues("ai.enabled=false", "ai.client.mode=http", "ai.client.base-url=http://127.0.0.1:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertDisabled(context);
                });
    }

    @Test
    void explicitHttpOptInRestoresAiRoutesWithoutStartingLocalModelsOrVectors() {
        runner.withPropertyValues("ai.enabled=true", "ai.client.mode=http", "ai.client.base-url=http://127.0.0.1:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiApplicationFacade.class).hasSingleBean(AiController.class)
                            .hasSingleBean(HttpBlogAiAdapter.class);
                    assertThat(context).doesNotHaveBean(InProcessAiAdapter.class).doesNotHaveBean(AiConfig.class)
                            .doesNotHaveBean(VectorStoreInitializer.class).doesNotHaveBean(ChatModel.class)
                            .doesNotHaveBean(EmbeddingModel.class).doesNotHaveBean(VectorStore.class);
                });
    }

    private void assertDisabled(org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        assertThat(context).doesNotHaveBean(AiApplicationFacade.class).doesNotHaveBean(AiController.class)
                .doesNotHaveBean(InProcessAiAdapter.class).doesNotHaveBean(HttpBlogAiAdapter.class)
                .doesNotHaveBean(SpringAiChatAdapter.class).doesNotHaveBean(AiConfig.class)
                .doesNotHaveBean(VectorStoreInitializer.class).doesNotHaveBean(ChatModel.class)
                .doesNotHaveBean(EmbeddingModel.class).doesNotHaveBean(VectorStore.class).doesNotHaveBean(ChatClient.Builder.class);
    }
}
