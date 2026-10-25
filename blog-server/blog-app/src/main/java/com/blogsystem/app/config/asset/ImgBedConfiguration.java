package com.blogsystem.app.config.asset;

import com.blogsystem.domain.asset.image.gateway.ImageStorageGateway;
import com.blogsystem.domain.asset.inventory.gateway.MediaInventoryGateway;
import com.blogsystem.infrastructure.configuration.asset.ImgBedSettings;
import com.blogsystem.infrastructure.gateway.imgbed.asset.CloudflareImageRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.CloudflareMediaInventoryRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.DeletionGuardedImageStorageGateway;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ImgBedConfiguration {
    @Bean
    ImageStorageGateway imageRepository(
            ObjectMapper mapper,
            @Value("${imgbed.base-url}") String baseUrl,
            @Value("${imgbed.api-token:}") String token,
            @Value("${imgbed.upload-channel:telegram}") String uploadChannel,
            @Value("${imgbed.channel-name:}") String channelName,
            @Value("${imgbed.root-folder:blog}") String rootFolder,
            @Value("${media.catalog.storage:legacy-json}") String storage) {
        return new DeletionGuardedImageStorageGateway(
                new CloudflareImageRepository(
                        new ImgBedSettings(
                                baseUrl,
                                token,
                                uploadChannel,
                                channelName,
                                rootFolder,
                                Duration.ofSeconds(30)),
                        mapper),
                "legacy-json".equals(storage));
    }

    @Bean
    MediaInventoryGateway mediaInventoryRepository(
            ObjectMapper mapper,
            @Value("${imgbed.base-url}") String baseUrl,
            @Value("${imgbed.api-token:}") String token,
            @Value("${imgbed.upload-channel:telegram}") String uploadChannel,
            @Value("${imgbed.channel-name:}") String channelName,
            @Value("${imgbed.root-folder:blog}") String rootFolder) {
        return new CloudflareMediaInventoryRepository(
                new ImgBedSettings(
                        baseUrl,
                        token,
                        uploadChannel,
                        channelName,
                        rootFolder,
                        Duration.ofSeconds(30)),
                mapper);
    }
}
