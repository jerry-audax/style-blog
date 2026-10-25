package com.blogsystem.app.config.asset;

import com.blogsystem.app.config.properties.MediaDeletionProperties;
import com.blogsystem.application.asset.deletion.service.MediaDeletionApplicationService;
import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionOperationRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceRepository;
import com.blogsystem.domain.music.adapter.repository.MusicTrackDeletionRepository;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoDeletionRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaDeletionAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaDeletionOperationRepository;
import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackDeletionRepository;
import com.blogsystem.infrastructure.adapter.repository.surprise.MybatisSurpriseVideoDeletionRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaDeletionConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionOperationMapper;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import com.blogsystem.infrastructure.mybatis.schema.MediaDeletionSchemaVerifier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(MediaDeletionProperties.class)
public class MediaDeletionRuntimeConfiguration {
    public MediaDeletionRuntimeConfiguration(MediaDeletionProperties properties) {
        if (properties.getPhysicalDelete().isEnabled())
            throw new IllegalStateException("MEDIA-05A不支持物理删除，必须保持关闭");
    }

    @Configuration
    @ConditionalOnProperty(name = "media.catalog.storage", havingValue = "postgres")
    static class Postgres {
        @Bean
        @ConditionalOnMissingBean(MediaDeletionSchemaVerifier.class)
        MediaDeletionSchemaVerifier mediaDeletionSchemaVerifier(DataSource ds) {
            return new MediaDeletionSchemaVerifier(ds);
        }

        @Bean
        MediaDeletionAssetRepository mediaDeletionAssetRepository(MediaDeletionAssetMapper mapper) {
            return new MybatisMediaDeletionAssetRepository(
                    mapper, new MediaDeletionConverter(new MediaAssetConverter()));
        }

        @Bean
        MediaDeletionOperationRepository mediaDeletionOperationRepository(
                MediaDeletionOperationMapper mapper) {
            return new MybatisMediaDeletionOperationRepository(
                    mapper, new MediaDeletionConverter(new MediaAssetConverter()));
        }

        @Bean
        MusicTrackDeletionRepository musicTrackDeletionRepository(MusicTrackMapper mapper) {
            return new MybatisMusicTrackDeletionRepository(mapper);
        }

        @Bean
        SurpriseVideoDeletionRepository surpriseVideoDeletionRepository(
                SurpriseVideoMapper mapper) {
            return new MybatisSurpriseVideoDeletionRepository(mapper);
        }

        @Bean
        MediaDeletionApplicationService mediaDeletionApplicationService(
                MediaDeletionAssetRepository assets,
                MediaDeletionOperationRepository operations,
                MusicTrackDeletionRepository music,
                SurpriseVideoDeletionRepository videos,
                @Qualifier("mediaCatalogTransactions") TransactionOperations transactions,
                MediaDeletionProperties properties,
                MediaDeletionSchemaVerifier verifier,
                MediaReferenceRepository references) {
            if (properties.isEnabled()) verifier.verify();
            return new MediaDeletionApplicationService(
                    assets,
                    operations,
                    music,
                    videos,
                    transactions,
                    Clock.systemUTC(),
                    properties.getEnvironmentId(),
                    properties.isEnabled(),
                    verifier::available,
                    references);
        }
    }
}
