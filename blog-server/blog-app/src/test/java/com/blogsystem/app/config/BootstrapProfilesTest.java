package com.blogsystem.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.blogsystem.app.config.ai.AiConfig;
import com.blogsystem.app.config.ai.AiRuntimeConfiguration;
import com.blogsystem.app.config.asset.MediaCatalogRuntimeConfiguration;
import com.blogsystem.app.config.asset.MediaReferenceWriteConfiguration;
import com.blogsystem.app.config.asset.MediaUploadRuntimeConfiguration;
import com.blogsystem.app.config.music.MusicConfiguration;
import com.blogsystem.app.config.surprise.SurpriseConfiguration;
import com.blogsystem.application.ai.service.AiApplicationFacade;
import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.application.music.port.MusicCatalogUseCase;
import com.blogsystem.application.music.service.HostedMusicApplicationService;
import com.blogsystem.application.music.service.MusicCatalogApplicationService;
import com.blogsystem.application.music.service.PersistentMusicCatalogApplicationService;
import com.blogsystem.application.surprise.service.SurpriseVideoApplicationService;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.domain.asset.upload.gateway.MediaUploadStorageGateway;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;
import com.blogsystem.domain.music.gateway.HostedAudioGateway;
import com.blogsystem.domain.music.gateway.MusicMediaGateway;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;
import com.blogsystem.domain.surprise.gateway.SurpriseVideoMediaGateway;
import com.blogsystem.infrastructure.configuration.music.MusicProperties;
import com.blogsystem.infrastructure.configuration.surprise.SurpriseProperties;
import com.blogsystem.infrastructure.filesystem.music.JsonMusicCatalogRepository;
import com.blogsystem.infrastructure.filesystem.surprise.JsonSurpriseVideoRepository;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import com.blogsystem.infrastructure.mybatis.schema.MediaCatalogSchemaVerifier;
import com.blogsystem.infrastructure.mybatis.schema.MediaUploadSchemaVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class BootstrapProfilesTest {
    private ApplicationContextRunner runner(DataSource dataSource) {
        return new ApplicationContextRunner().withUserConfiguration(
                MediaReferenceWriteConfiguration.class, MediaCatalogRuntimeConfiguration.class, MediaUploadRuntimeConfiguration.class,
                MusicConfiguration.class, SurpriseConfiguration.class, AiConfig.class, AiRuntimeConfiguration.class,
                MusicCatalogApplicationService.class, HostedMusicApplicationService.class, SurpriseVideoApplicationService.class,
                JsonMusicCatalogRepository.class, JsonSurpriseVideoRepository.class)
                .withPropertyValues("imgbed.base-url=https://provider.invalid", "ai.enabled=false")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(MusicProperties.class, MusicProperties::new)
                .withBean(SurpriseProperties.class, SurpriseProperties::new)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(MediaAssetRepository.class, () -> mock(MediaAssetRepository.class))
                .withBean(MediaDeletionAssetRepository.class, () -> mock(MediaDeletionAssetRepository.class))
                .withBean(MediaReferenceLinkGateway.class, () -> mock(MediaReferenceLinkGateway.class))
                .withBean(MusicTrackMapper.class, () -> mock(MusicTrackMapper.class))
                .withBean(SurpriseVideoMapper.class, () -> mock(SurpriseVideoMapper.class))
                .withBean(PlaylistSnapshotRepository.class, () -> mock(PlaylistSnapshotRepository.class))
                .withBean(SongArtworkGateway.class, () -> mock(SongArtworkGateway.class))
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                    // Actual legacy provider factories are replaced before instantiation.
                    for (var entry : java.util.Map.of("hostedAudioRepository", HostedAudioGateway.class,
                            "musicMediaRepository", MusicMediaGateway.class,
                            "surpriseVideoMediaRepository", SurpriseVideoMediaGateway.class).entrySet()) {
                        if (factory.containsBeanDefinition(entry.getKey())) {
                            ((AbstractBeanDefinition) factory.getBeanDefinition(entry.getKey()))
                                    .setInstanceSupplier(() -> mock(entry.getValue(), invocation -> {
                                        throw new AssertionError("A bootstrap profile invoked a remote provider");
                                    }));
                        }
                    }
                }));
    }

    @Test void defaultLegacyNeedsNeitherDatabaseNorUploadLedgerNorAi() {
        var dataSource = mock(DataSource.class);
        runner(dataSource).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JsonMusicCatalogRepository.class)
                    .doesNotHaveBean(PersistentMusicCatalogApplicationService.class)
                    .doesNotHaveBean(MediaCatalogSchemaVerifier.class).doesNotHaveBean(MediaUploadSchemaVerifier.class)
                    .doesNotHaveBean(MediaUploadOperationRepository.class).doesNotHaveBean(MediaUploadStorageGateway.class)
                    .doesNotHaveBean(AiApplicationFacade.class).doesNotHaveBean(ChatModel.class).doesNotHaveBean(EmbeddingModel.class);
            verifyNoInteractions(dataSource);
        });
    }

    @Test void postgresReadOnlyDoesNotRequireUploadLedgerOrInitializeAi() {
        var dataSource = mock(DataSource.class);
        var schema = mock(MediaCatalogSchemaVerifier.class);
        runner(dataSource).withBean(MediaCatalogSchemaVerifier.class, () -> schema)
                .withPropertyValues("media.catalog.storage=postgres", "media.catalog.uploads.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MusicCatalogUseCase.class)
                            .hasSingleBean(PersistentMusicCatalogApplicationService.class)
                            .doesNotHaveBean(JsonMusicCatalogRepository.class).doesNotHaveBean(MediaUploadApplicationService.class)
                            .doesNotHaveBean(MediaUploadStorageGateway.class).doesNotHaveBean(MediaUploadSchemaVerifier.class)
                            .doesNotHaveBean(AiApplicationFacade.class).doesNotHaveBean(ChatModel.class).doesNotHaveBean(EmbeddingModel.class);
                    verifyNoInteractions(dataSource);
                });
    }

    @Test void invalidPostgresSchemaFailsWithoutJsonFallback() throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("Fake invalid catalog schema"));
        var fallbackRegistered = new java.util.concurrent.atomic.AtomicBoolean(true);
        runner(dataSource).withPropertyValues("media.catalog.storage=postgres")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory ->
                        fallbackRegistered.set(factory.containsBeanDefinition("jsonMusicCatalogRepository"))))
                .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("媒体目录数据库结构缺失或不兼容");
            org.mockito.Mockito.verify(dataSource).getConnection();
            assertThat(fallbackRegistered).isFalse();
        });
    }
}
