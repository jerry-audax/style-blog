package com.blogsystem.app.config.asset;

import com.blogsystem.app.config.properties.MediaCatalogProperties;
import com.blogsystem.application.asset.inventory.service.MusicMediaReferenceSource;
import com.blogsystem.application.asset.inventory.service.SurpriseMediaReferenceSource;
import com.blogsystem.application.asset.metadata.result.MediaCatalogCapabilities;
import com.blogsystem.application.asset.image.result.ImageCatalogCapabilities;
import com.blogsystem.application.asset.metadata.service.MediaAssetQueryApplicationService;
import com.blogsystem.application.asset.upload.result.MediaUploadLimits;
import com.blogsystem.application.music.query.MusicReferenceQuery;
import com.blogsystem.application.music.service.PersistentMusicCatalogApplicationService;
import com.blogsystem.application.surprise.query.SurpriseReferenceQuery;
import com.blogsystem.application.surprise.service.PersistentSurpriseVideoApplicationService;
import com.blogsystem.domain.asset.inventory.gateway.MediaReferenceSource;
import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoCatalogRepository;
import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackRepository;
import com.blogsystem.infrastructure.adapter.repository.surprise.MybatisSurpriseVideoCatalogRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedMediaPublicUrlRepository;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import com.blogsystem.infrastructure.mybatis.schema.MediaCatalogSchemaVerifier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(MediaCatalogProperties.class)
public class MediaCatalogRuntimeConfiguration {
    @Bean
    MediaCatalogCapabilities mediaCatalogCapabilities(MediaCatalogProperties properties) {
        if (!"postgres".equals(properties.getStorage()))
            return new MediaCatalogCapabilities(true, false, true, true, true, true).withImages(ImageCatalogCapabilities.legacy());
        return (properties.getUploads().getEnabled()
                ? new MediaCatalogCapabilities(
                        true,
                        true,
                        true,
                        true,
                        false,
                        false,
                        "tracked-v1",
                        new MediaUploadLimits(104857600, 10485760, 524288, 209715200, 16000000))
                : new MediaCatalogCapabilities(true, true, false, false, false, false))
                .withImages(ImageCatalogCapabilities.postgres(properties.getUploads().getEnabled()));
    }

    @Configuration
    @ConditionalOnProperty(name = "media.catalog.storage", havingValue = "postgres")
    static class PostgresRuntime {
        @Bean
        MediaReferenceSource musicMediaReferenceSource(MusicReferenceQuery query) {
            return new MusicMediaReferenceSource(query);
        }

        @Bean
        MediaReferenceSource surpriseMediaReferenceSource(SurpriseReferenceQuery query) {
            return new SurpriseMediaReferenceSource(query);
        }

        @Bean(name = "mediaCatalogTransactions")
        TransactionOperations mediaCatalogTransactions(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }

        @Bean(initMethod = "verify")
        @ConditionalOnMissingBean(MediaCatalogSchemaVerifier.class)
        MediaCatalogSchemaVerifier mediaCatalogSchemaVerifier(DataSource ds) {
            return new MediaCatalogSchemaVerifier(ds);
        }

        @Bean
        MediaPublicUrlGateway mediaPublicUrlRepository(
                @Value("${imgbed.base-url}") String base,
                @Value("${imgbed.root-folder:blog}") String root) {
            return new ImgBedMediaPublicUrlRepository(URI.create(base), root);
        }

        @Bean
        MediaAssetQueryApplicationService mediaAssetQueryApplicationService(
                MediaAssetRepository assets, MediaPublicUrlGateway urls) {
            return new MediaAssetQueryApplicationService(assets, urls);
        }

        @Bean
        MusicTrackRepository musicTrackRepository(MusicTrackMapper mapper,
                com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository references) {
            return new MybatisMusicTrackRepository(mapper, references);
        }

        @Bean
        SurpriseVideoCatalogRepository surpriseVideoCatalogRepository(SurpriseVideoMapper mapper,
                com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository references) {
            return new MybatisSurpriseVideoCatalogRepository(mapper, references);
        }

        @Bean
        PersistentMusicCatalogApplicationService persistentMusicCatalogApplicationService(
                MusicTrackRepository tracks,
                MediaAssetQueryApplicationService assets,
                PlaylistSnapshotRepository snapshots,
                SongArtworkGateway artwork,
                MusicPolicy policy,
                @Qualifier("mediaCatalogTransactions") TransactionOperations transactions) {
            return new PersistentMusicCatalogApplicationService(
                    tracks, assets, snapshots, artwork, policy, Clock.systemUTC(), transactions);
        }

        @Bean
        PersistentSurpriseVideoApplicationService persistentSurpriseVideoApplicationService(
                SurpriseVideoCatalogRepository videos,
                MediaAssetQueryApplicationService assets,
                @Qualifier("mediaCatalogTransactions") TransactionOperations transactions) {
            return new PersistentSurpriseVideoApplicationService(
                    videos, assets, Clock.systemUTC(), transactions);
        }
    }
}
