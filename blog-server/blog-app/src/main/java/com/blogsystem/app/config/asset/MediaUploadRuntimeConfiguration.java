package com.blogsystem.app.config.asset;

import com.blogsystem.application.asset.upload.assembler.MediaUploadViewProjector;
import com.blogsystem.application.asset.image.service.ImageUploadBindingApplicationService;
import com.blogsystem.application.asset.upload.port.UploadBindingApplicationPort;
import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.application.asset.upload.service.MediaUploadLeaseRunner;
import com.blogsystem.application.music.service.MusicUploadBindingApplicationService;
import com.blogsystem.application.surprise.service.SurpriseUploadBindingApplicationService;
import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.upload.gateway.MediaUploadContentVerifier;
import com.blogsystem.domain.asset.upload.gateway.MediaUploadStorageGateway;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoCatalogRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaUploadOperationRepository;
import com.blogsystem.infrastructure.configuration.asset.MediaUploadProviderSettings;
import com.blogsystem.infrastructure.gateway.imgbed.asset.CloudflareMediaUploadRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.StreamingMediaUploadContentVerifier;
import com.blogsystem.infrastructure.mybatis.codec.asset.MediaUploadSnapshotCodec;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaUploadOperationConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaUploadOperationMapper;
import com.blogsystem.infrastructure.mybatis.schema.MediaUploadSchemaVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import javax.sql.DataSource;

@Configuration
@ConditionalOnExpression(
        "'${media.catalog.storage:legacy-json}' == 'postgres' &&"
                + " ${media.catalog.uploads.enabled:false}")
public class MediaUploadRuntimeConfiguration {
    @Bean(initMethod = "verify")
    @ConditionalOnMissingBean(MediaUploadSchemaVerifier.class)
    MediaUploadSchemaVerifier mediaUploadSchemaVerifier(DataSource ds) {
        return new MediaUploadSchemaVerifier(ds);
    }

    @Bean
    MediaUploadProviderSettings mediaUploadProviderSettings(
            @Value("${imgbed.base-url}") String base,
            @Value("${imgbed.api-token:}") String token,
            @Value("${imgbed.upload-channel:telegram}") String channel,
            @Value("${imgbed.channel-name:}") String name,
            @Value("${imgbed.root-folder:blog}") String root) {
        return new MediaUploadProviderSettings(
                base,
                token,
                channel,
                name,
                root,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofSeconds(120));
    }

    @Bean
    @ConditionalOnMissingBean(MediaUploadStorageGateway.class)
    MediaUploadStorageGateway mediaUploadStorageRepository(
            MediaUploadProviderSettings settings, ObjectMapper json) {
        return new CloudflareMediaUploadRepository(settings, json);
    }

    @Bean
    MediaUploadOperationRepository mediaUploadOperationRepository(
            MediaUploadOperationMapper mapper, ObjectMapper json) {
        return new MybatisMediaUploadOperationRepository(
                mapper, new MediaUploadOperationConverter(new MediaUploadSnapshotCodec(json)));
    }

    @Bean
    MediaUploadContentVerifier mediaUploadContentVerifier() {
        return new StreamingMediaUploadContentVerifier();
    }

    @Bean
    ImageUploadBindingApplicationService imageUploadBinding(
            MediaAssetRepository assets, MediaReferenceWriteRepository references) {
        return new ImageUploadBindingApplicationService(assets, references);
    }

    @Bean
    MusicUploadBindingApplicationService musicUploadBinding(
            MusicTrackRepository tracks, MediaAssetRepository assets) {
        return new MusicUploadBindingApplicationService(tracks, assets);
    }

    @Bean
    SurpriseUploadBindingApplicationService surpriseUploadBinding(
            SurpriseVideoCatalogRepository videos, MediaAssetRepository assets) {
        return new SurpriseUploadBindingApplicationService(videos, assets, UUID::randomUUID);
    }

    @Bean(name = "mediaUploadHeartbeat", destroyMethod = "shutdownNow")
    ScheduledExecutorService mediaUploadHeartbeat() {
        var pool =
                new ScheduledThreadPoolExecutor(
                        1,
                        r -> {
                            var t = new Thread(r, "media-upload-heartbeat");
                            t.setDaemon(true);
                            return t;
                        });
        pool.setRemoveOnCancelPolicy(true);
        pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return pool;
    }

    @Bean
    MediaUploadLeaseRunner mediaUploadLeaseRunner(
            MediaUploadOperationRepository operations,
            @Qualifier("mediaCatalogTransactions") TransactionOperations transactions,
            @Qualifier("mediaUploadHeartbeat") ScheduledExecutorService scheduler) {
        return new MediaUploadLeaseRunner(operations, transactions, scheduler);
    }

    @Bean
    MediaUploadApplicationService mediaUploadApplicationService(
            MediaUploadOperationRepository operations,
            MediaAssetRepository assets,
            List<UploadBindingApplicationPort> bindings,
            MediaUploadStorageGateway storage,
            MediaUploadContentVerifier verifier,
            @Qualifier("mediaCatalogTransactions") TransactionOperations transactions,
            MediaUploadLeaseRunner runner,
            MediaUploadProviderSettings settings,
            MediaPublicUrlGateway urls) {
        return new MediaUploadApplicationService(
                operations,
                assets,
                bindings,
                storage,
                verifier,
                transactions,
                runner,
                Clock.systemUTC(),
                UUID::randomUUID,
                "cloudflare_imgbed",
                new MediaObjectKey(settings.rootFolder),
                urls);
    }

    @Bean
    MediaUploadViewProjector mediaUploadViewProjector(MediaPublicUrlGateway urls) {
        return new MediaUploadViewProjector(urls);
    }
}
