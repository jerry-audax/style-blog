package com.blogsystem.app.config.asset;

import com.blogsystem.app.config.properties.MediaReferenceProperties;
import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationPinRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceRepository;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaPublicationPinRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaReferenceRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedReferenceLinkGateway;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaPublicationPinConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaPublicationPinMapper;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaReferenceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

@Configuration
@ConditionalOnProperty(name = "media.catalog.storage", havingValue = "postgres")
@EnableConfigurationProperties(MediaReferenceProperties.class)
public class MediaReferenceConfiguration {
    @Bean
    MediaReferenceLinkGateway mediaReferenceLinkGateway(@Value("${imgbed.base-url}") URI base,
            @Value("${imgbed.root-folder:blog}") String root, MediaReferenceProperties properties) {
        Set<URI> origins = new HashSet<>(); origins.add(base);
        for (String origin : properties.getAllowedOrigins()) origins.add(URI.create(origin));
        return new ImgBedReferenceLinkGateway(origins, root, properties.getMaxContentChars(), properties.getMaxLinks());
    }

    @Bean
    MediaPublicationPinRepository mediaPublicationPinRepository(MediaPublicationPinMapper mapper) {
        return new MybatisMediaPublicationPinRepository(mapper, new MediaPublicationPinConverter());
    }

    @Bean
    MediaReferenceRepository mediaReferenceRepository(MediaReferenceMapper mapper, MediaReferenceLinkGateway links,
            MediaPublicationPinRepository pins) {
        return new MybatisMediaReferenceRepository(mapper, links, pins);
    }
}
