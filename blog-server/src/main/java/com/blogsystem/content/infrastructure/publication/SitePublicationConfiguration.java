package com.blogsystem.content.infrastructure.publication;

import com.blogsystem.content.domain.repository.SitePublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.nio.file.*;

@Configuration
public class SitePublicationConfiguration {
    @Bean
    SitePublicationRepository sitePublicationRepository(ObjectMapper json, Environment environment,
            @Value("${publication.base-url:http://127.0.0.1:5176}") String url,
            @Value("${publication.api-token:}") String token,
            @Value("${publication.token-file:../blog-web/.runtime/publication.key}") String tokenFile) {
        return new HttpSitePublicationRepository(json, URI.create(url), () -> {
            if (!token.isBlank()) return token;
            if (!environment.matchesProfiles("dev")) return "";
            try {
                Path file = Path.of(tokenFile).toAbsolutePath().normalize();
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 256) return "";
                return Files.readString(file).strip();
            }
            catch (Exception ignored) {
                return "";
            }
        });
    }
}
