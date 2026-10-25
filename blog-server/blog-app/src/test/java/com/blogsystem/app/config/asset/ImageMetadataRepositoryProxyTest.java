package com.blogsystem.app.config.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.blogsystem.domain.asset.image.repository.ImageMetadataRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisImageMetadataRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.ImageMetadataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ImageMetadataRepositoryProxyTest {
    @Test void postgresImageRepositoryStartsWithTheProductionExceptionTranslationProxy() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                        PersistenceExceptionTranslationAutoConfiguration.class))
                .withUserConfiguration(MybatisImageMetadataRepository.class)
                .withBean(ImageMetadataMapper.class, () -> mock(ImageMetadataMapper.class))
                .withBean(MediaAssetConverter.class, MediaAssetConverter::new)
                .withPropertyValues("media.catalog.storage=postgres", "spring.aop.proxy-target-class=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ImageMetadataRepository.class);
                    assertThat(AopUtils.isCglibProxy(context.getBean(ImageMetadataRepository.class))).isTrue();
                });
    }
}
