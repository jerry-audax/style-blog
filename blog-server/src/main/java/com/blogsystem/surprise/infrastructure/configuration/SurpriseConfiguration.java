package com.blogsystem.surprise.infrastructure.configuration;

import com.blogsystem.asset.infrastructure.configuration.ImgBedSettings;
import com.blogsystem.surprise.domain.repository.SurpriseVideoMediaRepository;
import com.blogsystem.surprise.infrastructure.imgbed.CloudflareSurpriseVideoMediaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SurpriseConfiguration {
    @Bean
    SurpriseVideoMediaRepository surpriseVideoMediaRepository(ObjectMapper mapper,
                                                               @Value("${imgbed.base-url}") String baseUrl,
                                                               @Value("${imgbed.api-token:}") String token,
                                                               @Value("${imgbed.upload-channel:telegram}") String channel,
                                                               @Value("${imgbed.channel-name:TelegramBot}") String channelName,
                                                               @Value("${imgbed.root-folder:blog}") String root) {
        return new CloudflareSurpriseVideoMediaRepository(new ImgBedSettings(baseUrl, token, channel, channelName, root, Duration.ofSeconds(60)), mapper);
    }
}
