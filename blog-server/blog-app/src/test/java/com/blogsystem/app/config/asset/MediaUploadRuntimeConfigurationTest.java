package com.blogsystem.app.config.asset;

import com.blogsystem.infrastructure.mybatis.schema.MediaCatalogSchemaVerifier;
import com.blogsystem.infrastructure.mybatis.schema.MediaUploadSchemaVerifier;

import com.blogsystem.app.it.application.asset.upload.service.FakeMediaUploadStorageRepository;

import com.blogsystem.application.asset.metadata.result.MediaCatalogCapabilities;
import com.blogsystem.application.asset.upload.result.MediaUploadLimits;
import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;

import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.upload.gateway.MediaUploadStorageGateway;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;

import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaUploadOperationMapper;
import com.blogsystem.trigger.http.asset.MediaUploadController;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MediaUploadRuntimeConfigurationTest {
    @Test void legacyAndReadOnlyPostgresDoNotConstructUploadProvider() {
        var constructions = new java.util.concurrent.atomic.AtomicInteger();
        for (String mode : new String[]{"legacy-json", "postgres"}) {
            runner().withPropertyValues("media.catalog.storage=" + mode, "media.catalog.uploads.enabled=false")
                    .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                        if (factory.containsBeanDefinition("mediaUploadStorageRepository")) {
                            ((org.springframework.beans.factory.support.AbstractBeanDefinition)
                                    factory.getBeanDefinition("mediaUploadStorageRepository"))
                                    .setInstanceSupplier(() -> {
                                        constructions.incrementAndGet();
                                        throw new AssertionError("Disabled uploads constructed a provider");
                                    });
                        }
                    })).run(context -> {
                        assertThat(context).hasNotFailed().doesNotHaveBean(MediaUploadStorageGateway.class);
                        assertThat(constructions.get()).isZero();
                    });
        }
    }
    ApplicationContextRunner runner(){return new ApplicationContextRunner().withUserConfiguration(MediaReferenceWriteConfiguration.class,MediaCatalogRuntimeConfiguration.class,MediaUploadRuntimeConfiguration.class,MediaUploadController.class)
            .withPropertyValues("imgbed.base-url=https://example.test","imgbed.channel-name=configured","imgbed.api-token=fake-token")
            .withBean(ObjectMapper.class,ObjectMapper::new).withBean(IdentityApplicationFacade.class,()->mock(IdentityApplicationFacade.class))
            .withBean(DataSource.class,()->mock(DataSource.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
            .withBean(MediaCatalogSchemaVerifier.class,()->mock(MediaCatalogSchemaVerifier.class)).withBean(MediaUploadSchemaVerifier.class,()->mock(MediaUploadSchemaVerifier.class))
            .withBean(MediaAssetRepository.class,()->mock(MediaAssetRepository.class)).withBean(MusicTrackMapper.class,()->mock(MusicTrackMapper.class)).withBean(SurpriseVideoMapper.class,()->mock(SurpriseVideoMapper.class)).withBean(MediaUploadOperationMapper.class,()->mock(MediaUploadOperationMapper.class))
            .withBean(com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository.class,()->mock(com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository.class))
            .withBean(com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway.class,()->mock(com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway.class))
            .withBean(PlaylistSnapshotRepository.class,()->mock(PlaylistSnapshotRepository.class)).withBean(SongArtworkGateway.class,()->mock(SongArtworkGateway.class)).withBean(MusicPolicy.class,()->mock(MusicPolicy.class));}
    @Test void disabledModeHasNoUploadBeansAndNeedsNoLedger(){
        for(String mode:new String[]{"legacy-json","postgres"})runner().withPropertyValues("media.catalog.storage="+mode).run(ctx->{assertThat(ctx).hasNotFailed().doesNotHaveBean(MediaUploadApplicationService.class).doesNotHaveBean(MediaUploadController.class).doesNotHaveBean(MediaUploadOperationRepository.class);assertThat(ctx.getBean(MediaCatalogCapabilities.class).uploadProtocol()).isNull();verifyNoInteractions(ctx.getBean(MediaUploadSchemaVerifier.class));});
        runner().withPropertyValues("media.catalog.storage=legacy-json","media.catalog.uploads.enabled=true").run(ctx->assertThat(ctx).doesNotHaveBean(MediaUploadApplicationService.class));
    }
    @Test void enabledFakeContextIsAcyclicAndSingleDataSource(){
        var storage=new FakeMediaUploadStorageRepository();var scheduler=new java.util.concurrent.atomic.AtomicReference<ScheduledExecutorService>();
        runner().withBean(MediaUploadStorageGateway.class,()->storage).withPropertyValues("media.catalog.storage=postgres","media.catalog.uploads.enabled=true").run(ctx->{
            assertThat(ctx).hasNotFailed().hasSingleBean(MediaUploadController.class).hasSingleBean(MediaUploadApplicationService.class).hasSingleBean(MediaUploadOperationRepository.class).hasSingleBean(DataSource.class).hasSingleBean(MediaUploadStorageGateway.class);
            assertThat(ctx.getBean(MediaUploadStorageGateway.class)).isSameAs(storage);scheduler.set(ctx.getBean("mediaUploadHeartbeat",ScheduledExecutorService.class));assertThat(scheduler.get().isShutdown()).isFalse();
            var cap=ctx.getBean(MediaCatalogCapabilities.class);assertThat(cap.uploadProtocol()).isEqualTo("tracked-v1");assertThat(cap.mediaUpload()).isTrue();assertThat(cap.mediaReplace()).isTrue();assertThat(cap.mediaDelete()).isFalse();assertThat(cap.catalogDelete()).isFalse();assertThat(cap.uploadLimits().chunkBytes()).isEqualTo(16000000);
        });assertThat(scheduler.get().isShutdown()).isTrue();
    }
    @Test void capabilityCanNeverEnableDelete(){runner().withPropertyValues("media.catalog.storage=postgres","media.catalog.uploads.enabled=true").run(ctx->{assertThat(ctx).hasNotFailed();var cap=ctx.getBean(MediaCatalogCapabilities.class);assertThat(cap.mediaDelete()).isFalse();assertThat(cap.catalogDelete()).isFalse();assertThat(cap.uploadLimits()).isEqualTo(new MediaUploadLimits(104857600,10485760,524288,209715200,16000000));});}
}
