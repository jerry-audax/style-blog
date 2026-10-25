package com.blogsystem.app.config.asset;

import com.blogsystem.app.config.content.PublicationCredentialSupplier;
import com.blogsystem.app.config.properties.MediaPublicationProperties;
import com.blogsystem.application.asset.publication.service.MediaPublicationMachineApplicationService;
import com.blogsystem.application.asset.publication.service.MediaPublicationPinApplicationService;
import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationManifestRepository;
import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationPinRepository;
import com.blogsystem.domain.asset.publication.model.gateway.PublicationMachineCredentialVerifier;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaPublicationManifestRepository;
import com.blogsystem.infrastructure.security.publication.PublicationMachineAuthenticator;
import com.blogsystem.trigger.interceptor.PublicationMachineInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(name = "media.catalog.publication-pins.enabled", havingValue = "true")
@EnableConfigurationProperties(MediaPublicationProperties.class)
public class MediaPublicationMachineConfiguration {
    public MediaPublicationMachineConfiguration(MediaPublicationProperties properties,
            @Value("${media.catalog.storage:legacy-json}") String storage) {
        if (!"postgres".equals(storage)) throw new IllegalStateException("发布保护机器接口要求PostgreSQL模式");
        try { new MediaPublicationPinKey(properties.getEnvironmentId(), properties.getPublisherId(), "configuration-check"); }
        catch (RuntimeException failure) { throw new IllegalStateException("发布保护机器身份未正确配置"); }
    }

    @Bean
    MediaPublicationManifestRepository mediaPublicationManifestRepository(MediaAssetRepository assets,
            MediaReferenceLinkGateway links, @Value("${imgbed.root-folder:blog}") String root) {
        return new MybatisMediaPublicationManifestRepository(assets, links, "cloudflare_imgbed", new MediaObjectKey(root));
    }

    @Bean
    MediaPublicationPinApplicationService mediaPublicationPinApplicationService(MediaPublicationPinRepository pins,
            MediaDeletionAssetRepository assets, PlatformTransactionManager manager) {
        var transactions = new TransactionTemplate(manager); transactions.setTimeout(10);
        return new MediaPublicationPinApplicationService(pins, assets, transactions, Clock.systemUTC());
    }

    @Bean
    MediaPublicationMachineApplicationService mediaPublicationMachineApplicationService(MediaPublicationPinApplicationService pins,
            MediaPublicationManifestRepository manifests, MediaPublicationProperties properties) {
        return new MediaPublicationMachineApplicationService(pins, manifests, properties.getEnvironmentId(), properties.getPublisherId());
    }

    @Bean
    PublicationMachineCredentialVerifier publicationMachineCredentialVerifier(PublicationCredentialSupplier credential) {
        var authenticator = new PublicationMachineAuthenticator(credential); authenticator.requireConfigured(); return authenticator;
    }

    @Bean
    PublicationMachineInterceptor publicationMachineInterceptor(PublicationMachineCredentialVerifier credential) {
        return new PublicationMachineInterceptor(credential);
    }

    @Bean
    WebMvcConfigurer publicationMachineMvcConfigurer(PublicationMachineInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/internal/media-publications", "/internal/media-publications/**");
            }
        };
    }
}
