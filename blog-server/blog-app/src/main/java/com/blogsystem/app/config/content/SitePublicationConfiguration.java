package com.blogsystem.app.config.content;

import com.blogsystem.domain.content.gateway.SitePublicationGateway;
import com.blogsystem.infrastructure.gateway.publication.HttpSitePublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;

@Configuration
public class SitePublicationConfiguration {
    @Bean
    PublicationCredentialSupplier publicationCredentialSupplier(
            Environment environment,
            @Value("${publication.api-token:}") String token,
            @Value("${publication.token-file:../blog-web/.runtime/publication.key}")
                    String tokenFile) {
        return new PublicationCredentialSupplier(token, environment.matchesProfiles("dev"), tokenFile);
    }

    @Bean
    SitePublicationGateway sitePublicationRepository(ObjectMapper json, PublicationCredentialSupplier credential,
            @Value("${publication.base-url:http://127.0.0.1:5176}") String url) {
        return new HttpSitePublicationRepository(json, URI.create(url), credential);
    }
}
