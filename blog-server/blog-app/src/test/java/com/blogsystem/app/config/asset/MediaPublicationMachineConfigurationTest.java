package com.blogsystem.app.config.asset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.blogsystem.app.config.content.PublicationCredentialSupplier;
import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationPinRepository;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.trigger.http.asset.MediaPublicationMachineController;
import com.blogsystem.trigger.http.asset.MediaPublicationMachineExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class MediaPublicationMachineConfigurationTest {
    final MediaAssetRepository metadata = mock(MediaAssetRepository.class);
    final MediaDeletionAssetRepository locks = mock(MediaDeletionAssetRepository.class);
    final MediaPublicationPinRepository pins = mock(MediaPublicationPinRepository.class);
    final String token = "isolated-machine-configuration-test-key-12345";

    @Configuration @EnableWebMvc static class Mvc {}

    WebApplicationContextRunner runner(String credential) {
        return new WebApplicationContextRunner().withUserConfiguration(Mvc.class,
                MediaPublicationMachineConfiguration.class, MediaPublicationMachineController.class, MediaPublicationMachineExceptionHandler.class)
                .withBean(MediaAssetRepository.class, () -> metadata).withBean(MediaDeletionAssetRepository.class, () -> locks)
                .withBean(MediaPublicationPinRepository.class, () -> pins)
                .withBean(MediaReferenceLinkGateway.class, () -> mock(MediaReferenceLinkGateway.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(PublicationCredentialSupplier.class, () -> new PublicationCredentialSupplier(credential, false, "unused"));
    }

    WebApplicationContextRunner enabled(String credential) {
        return runner(credential).withPropertyValues("media.catalog.publication-pins.enabled=true", "media.catalog.storage=postgres",
                "media.catalog.publication-pins.environment-id=local", "media.catalog.publication-pins.publisher-id=hexo");
    }

    @Test void defaultOffHasNoMachineRouteEvenWithDependenciesAvailable() {
        runner(token).run(context -> {
            assertNull(context.getStartupFailure()); assertTrue(context.getBeansOfType(MediaPublicationMachineController.class).isEmpty());
            MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).build()
                    .perform(post("/internal/media-publications/prepare")).andExpect(status().isNotFound());
            verifyNoInteractions(metadata, locks, pins);
        });
    }

    @Test void enabledRoutesUseMachineInterceptorBeforeMalformedBodyOrAnyRepository() {
        enabled(token).run(context -> {
            assertNull(context.getStartupFailure());
            var mvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).build();
            mvc.perform(post("/internal/media-publications/prepare").content("not-json")).andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/media-publications/prepare").header("Authorization", "owner-session").content("not-json"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/media-publications/prepare").header("X-Publication-Key", token).header("Origin", "https://blog.example").content("not-json"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(metadata, locks, pins);
        });
    }

    @Test void missingCredentialOrIdentityRefusesStartupWithoutDatabaseCalls() {
        enabled("").run(context -> assertNotNull(context.getStartupFailure()));
        enabled(token).withPropertyValues("media.catalog.publication-pins.publisher-id=")
                .run(context -> assertNotNull(context.getStartupFailure()));
        enabled(token).withPropertyValues("media.catalog.publication-pins.environment-id=")
                .run(context -> assertNotNull(context.getStartupFailure()));
        verifyNoInteractions(metadata, locks, pins);
    }

    @Test void legacyOrUnknownStorageNeverGetsAnEmptyProtectionImplementation() {
        for (String storage : java.util.List.of("legacy-json", "unknown"))
            enabled(token).withPropertyValues("media.catalog.storage=" + storage).run(context -> assertNotNull(context.getStartupFailure()));
        verifyNoInteractions(metadata, locks, pins);
    }

    @Test void enabledWithoutPersistenceDependenciesRefusesStartup() {
        new WebApplicationContextRunner().withUserConfiguration(MediaPublicationMachineConfiguration.class)
                .withPropertyValues("media.catalog.publication-pins.enabled=true", "media.catalog.storage=postgres",
                        "media.catalog.publication-pins.environment-id=local", "media.catalog.publication-pins.publisher-id=hexo")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
