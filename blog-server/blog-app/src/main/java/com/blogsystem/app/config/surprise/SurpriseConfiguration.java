package com.blogsystem.app.config.surprise;

import com.blogsystem.domain.surprise.gateway.SurpriseVideoMediaGateway;
import com.blogsystem.infrastructure.configuration.asset.ImgBedSettings;
import com.blogsystem.infrastructure.gateway.imgbed.surprise.CloudflareSurpriseVideoMediaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SurpriseConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "media.catalog.storage",
            havingValue = "legacy-json",
            matchIfMissing = true)
    SurpriseVideoMediaGateway surpriseVideoMediaRepository(
            ObjectMapper mapper,
            @Value("${imgbed.base-url}") String baseUrl,
            @Value("${imgbed.api-token:}") String token,
            @Value("${imgbed.upload-channel:telegram}") String channel,
            @Value("${imgbed.channel-name:TelegramBot}") String channelName,
            @Value("${imgbed.root-folder:blog}") String root) {
        return new CloudflareSurpriseVideoMediaRepository(
                new ImgBedSettings(
                        baseUrl, token, channel, channelName, root, Duration.ofSeconds(60)),
                mapper);
    }
}
