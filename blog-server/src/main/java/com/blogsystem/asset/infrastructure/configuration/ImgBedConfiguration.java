package com.blogsystem.asset.infrastructure.configuration;

import com.blogsystem.asset.domain.repository.ImageRepository;
import com.blogsystem.asset.infrastructure.imgbed.CloudflareImageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ImgBedConfiguration {
    @Bean
    ImageRepository imageRepository(ObjectMapper mapper,
                                    @Value("${imgbed.base-url}") String baseUrl,
                                    @Value("${imgbed.api-token:}") String token,
                                    @Value("${imgbed.upload-channel:telegram}") String uploadChannel,
                                    @Value("${imgbed.channel-name:}") String channelName,
                                    @Value("${imgbed.root-folder:blog}") String rootFolder) {
        return new CloudflareImageRepository(new ImgBedSettings(baseUrl, token, uploadChannel, channelName, rootFolder, Duration.ofSeconds(30)), mapper);
    }
}
