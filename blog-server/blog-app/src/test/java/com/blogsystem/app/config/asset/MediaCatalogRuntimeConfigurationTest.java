package com.blogsystem.app.config.asset;

import com.blogsystem.app.config.music.MusicConfiguration;
import com.blogsystem.app.config.surprise.SurpriseConfiguration;

import com.blogsystem.infrastructure.configuration.music.MusicProperties;
import com.blogsystem.infrastructure.configuration.surprise.SurpriseProperties;
import com.blogsystem.infrastructure.filesystem.asset.MusicCatalogMediaReferenceSource;
import com.blogsystem.infrastructure.filesystem.asset.SurpriseVideoMediaReferenceSource;
import com.blogsystem.infrastructure.filesystem.music.JsonMusicCatalogRepository;
import com.blogsystem.infrastructure.filesystem.surprise.JsonSurpriseVideoRepository;

import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import com.blogsystem.infrastructure.mybatis.schema.MediaCatalogSchemaVerifier;

import com.blogsystem.application.asset.inventory.service.MusicMediaReferenceSource;
import com.blogsystem.application.asset.inventory.service.SurpriseMediaReferenceSource;
import com.blogsystem.application.asset.metadata.result.MediaCatalogCapabilities;
import com.blogsystem.application.asset.metadata.service.MediaAssetQueryApplicationService;
import com.blogsystem.application.music.port.HostedMusicQueryUseCase;
import com.blogsystem.application.music.port.MusicCatalogUseCase;
import com.blogsystem.application.music.service.HostedMusicApplicationService;
import com.blogsystem.application.music.service.MusicCatalogApplicationService;
import com.blogsystem.application.music.service.PersistentMusicCatalogApplicationService;
import com.blogsystem.application.surprise.port.SurpriseVideoUseCase;
import com.blogsystem.application.surprise.service.SurpriseVideoApplicationService;

import com.blogsystem.domain.music.gateway.HostedAudioGateway;
import com.blogsystem.domain.music.gateway.MusicMediaGateway;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;
import com.blogsystem.domain.surprise.gateway.SurpriseVideoMediaGateway;

import java.util.Map;

import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MediaCatalogRuntimeConfigurationTest {
    private ApplicationContextRunner withLocalDevelopmentConfiguration() {
        return runner.withPropertyValues("imgbed.upload-channel=telegram", "imgbed.root-folder=blog")
                .withInitializer(context -> {
            try {
                var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
                for (var source : loader.load("local-development", new org.springframework.core.io.ClassPathResource("application-dev.yml"))) {
                    context.getEnvironment().getPropertySources().addLast(source);
                }
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
    }

    @Test void localDevelopmentReadsRegisteredMediaWithoutAnExtraIdeFlag() {
        withLocalDevelopmentConfiguration().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MusicCatalogUseCase.class))
                    .isInstanceOf(PersistentMusicCatalogApplicationService.class);
            assertThat(context).doesNotHaveBean(HostedAudioGateway.class)
                    .doesNotHaveBean(JsonMusicCatalogRepository.class);
            assertThat(context.getBean(MediaCatalogCapabilities.class))
                    .isEqualTo(new MediaCatalogCapabilities(true,true,false,false,false,false).withImages(com.blogsystem.application.asset.image.result.ImageCatalogCapabilities.postgres(false)));
        });
    }

    @Test void localDevelopmentAllowsAnExplicitLegacyOverride() {
        withLocalDevelopmentConfiguration().withPropertyValues("MEDIA_CATALOG_STORAGE=legacy-json")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MusicCatalogUseCase.class))
                            .isInstanceOf(MusicCatalogApplicationService.class);
                    assertThat(context).hasSingleBean(JsonMusicCatalogRepository.class);
                });
    }

    final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(MediaReferenceWriteConfiguration.class,MediaCatalogRuntimeConfiguration.class,MusicConfiguration.class,SurpriseConfiguration.class,
                    MusicCatalogApplicationService.class,HostedMusicApplicationService.class,SurpriseVideoApplicationService.class,
                    JsonMusicCatalogRepository.class,JsonSurpriseVideoRepository.class,MusicCatalogMediaReferenceSource.class,
                    SurpriseVideoMediaReferenceSource.class)
            .withPropertyValues("imgbed.base-url=https://example.test")
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.databind.ObjectMapper::new)
            .withBean(MusicProperties.class,MusicProperties::new).withBean(SurpriseProperties.class,SurpriseProperties::new)
            .withBean(PlaylistSnapshotRepository.class,()->mock(PlaylistSnapshotRepository.class))
            .withBean(SongArtworkGateway.class,()->ids->Map.of())
            .withBean(DataSource.class,()->mock(DataSource.class))
            .withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
            .withBean(MediaCatalogSchemaVerifier.class,()->mock(MediaCatalogSchemaVerifier.class))
            .withBean(MediaAssetRepository.class,()->mock(MediaAssetRepository.class))
            .withBean(com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository.class,()->mock(com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository.class))
            .withBean(com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway.class,()->mock(com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway.class))
            .withBean(MusicTrackMapper.class,()->mock(MusicTrackMapper.class))
            .withBean(SurpriseVideoMapper.class,()->mock(SurpriseVideoMapper.class));
    @Test void postgresHasExactlyOneRuntimeAndNoLegacyProviderWrites(){
        runner.withPropertyValues("media.catalog.storage=postgres").run(ctx->{
            assertThat(ctx).hasNotFailed().hasSingleBean(MusicCatalogUseCase.class).hasSingleBean(HostedMusicQueryUseCase.class).hasSingleBean(SurpriseVideoUseCase.class);
            assertThat(ctx.getBean(com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository.class))
                    .isInstanceOf(com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaReferenceWriteRepository.class);
            assertThat(ctx.getBean(MusicCatalogUseCase.class)).isInstanceOf(PersistentMusicCatalogApplicationService.class);
            assertThat(ctx).doesNotHaveBean(JsonMusicCatalogRepository.class).doesNotHaveBean(JsonSurpriseVideoRepository.class)
                    .doesNotHaveBean(HostedAudioGateway.class).doesNotHaveBean(MusicMediaGateway.class).doesNotHaveBean(SurpriseVideoMediaGateway.class);
            assertThat(ctx).hasSingleBean(MusicMediaReferenceSource.class).hasSingleBean(SurpriseMediaReferenceSource.class)
                    .doesNotHaveBean(MusicCatalogMediaReferenceSource.class).doesNotHaveBean(SurpriseVideoMediaReferenceSource.class);
            assertThat(ctx.getBean(MediaCatalogCapabilities.class)).isEqualTo(new MediaCatalogCapabilities(true,true,false,false,false,false).withImages(com.blogsystem.application.asset.image.result.ImageCatalogCapabilities.postgres(false)));
        });
    }
    @Test void legacyIsExplicitDefaultAndDoesNotLoadDatabaseRuntime(){
        runner.run(ctx->{assertThat(ctx).hasNotFailed().hasSingleBean(MusicCatalogUseCase.class).hasSingleBean(SurpriseVideoUseCase.class);
            assertThat(ctx.getBean(MusicCatalogUseCase.class)).isInstanceOf(MusicCatalogApplicationService.class);
            assertThat(ctx).hasSingleBean(JsonMusicCatalogRepository.class).hasSingleBean(JsonSurpriseVideoRepository.class)
                    .hasSingleBean(HostedAudioGateway.class).doesNotHaveBean(PersistentMusicCatalogApplicationService.class).doesNotHaveBean(MediaAssetQueryApplicationService.class);
            assertThat(ctx.containsBean("mediaCatalogTransactions")).isFalse();
            assertThat(ctx.getBean(MediaCatalogCapabilities.class)).isEqualTo(new MediaCatalogCapabilities(true,false,true,true,true,true).withImages(com.blogsystem.application.asset.image.result.ImageCatalogCapabilities.legacy()));});
    }
    @Test void unknownOrNormalizedModeIsRejectedRatherThanSilentlyFallingBack(){
        for(String mode:new String[]{"invalid","POSTGRES"," postgres "})runner.withInitializer(ctx->ctx.getEnvironment().getPropertySources()
                .addFirst(new org.springframework.core.env.MapPropertySource("raw-mode",Map.of("media.catalog.storage",mode))))
                .run(ctx->assertThat(ctx).as("Exact raw mode: %s",mode).hasFailed());
    }
    @Test void postgresCannotStartWithoutReferenceProtection() {
        runner.withPropertyValues("media.catalog.storage=postgres", "media.catalog.uploads.enabled=false")
                .withInitializer(ctx -> ctx.addBeanFactoryPostProcessor(factory ->
                        ((org.springframework.beans.factory.support.BeanDefinitionRegistry) factory)
                                .removeBeanDefinition("postgresMediaReferenceWriteRepository")))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
