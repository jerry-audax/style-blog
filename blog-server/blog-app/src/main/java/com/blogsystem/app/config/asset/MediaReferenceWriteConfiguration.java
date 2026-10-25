package com.blogsystem.app.config.asset;

import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaReferenceWriteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MediaReferenceWriteConfiguration {
    @Bean
    @ConditionalOnProperty(name = "media.catalog.storage", havingValue = "postgres")
    MediaReferenceWriteRepository postgresMediaReferenceWriteRepository(MediaAssetRepository assets,
            MediaDeletionAssetRepository locks, MediaReferenceLinkGateway links,
            @Value("${imgbed.root-folder:blog}") String root) {
        return new MybatisMediaReferenceWriteRepository(assets, locks, links, "cloudflare_imgbed", new MediaObjectKey(root));
    }

    @Bean
    @ConditionalOnProperty(name = "media.catalog.storage", havingValue = "legacy-json", matchIfMissing = true)
    MediaReferenceWriteRepository legacyMediaReferenceWriteRepository() {
        // Explicit compatibility mode only. Unknown storage and postgres never use this branch.
        return (previous, desired) -> {};
    }
}
