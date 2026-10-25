package com.blogsystem.app.config.asset;

import com.blogsystem.application.asset.image.service.ImageCatalogQueryApplicationService;
import com.blogsystem.domain.asset.image.repository.ImageMetadataRepository;
import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "media.catalog.storage", havingValue = "postgres")
public class ImageMetadataConfiguration {
    @Bean
    ImageCatalogQueryApplicationService imageCatalogQueryApplicationService(ImageMetadataRepository images,
            MediaPublicUrlGateway urls, @Value("${imgbed.root-folder:blog}") String root) {
        return new ImageCatalogQueryApplicationService(images, urls, "cloudflare_imgbed", new MediaObjectKey(root));
    }
}
