package com.blogsystem.music.infrastructure.configuration;

import com.blogsystem.music.domain.model.MusicPolicy;
import com.blogsystem.music.domain.repository.HostedAudioRepository;
import com.blogsystem.music.domain.repository.MusicCatalogRepository;
import com.blogsystem.music.domain.repository.MusicMediaRepository;
import com.blogsystem.music.infrastructure.imgbed.CloudflareHostedAudioRepository;
import com.blogsystem.music.infrastructure.imgbed.CloudflareMusicMediaRepository;
import com.blogsystem.asset.infrastructure.configuration.ImgBedSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MusicConfiguration {
    @Bean
    HostedAudioRepository hostedAudioRepository(ObjectMapper json,
                                                @Value("${imgbed.base-url}") String baseUrl,
                                                @Value("${imgbed.api-token:}") String token,
                                                @Value("${imgbed.upload-channel:telegram}") String channel,
                                                @Value("${imgbed.channel-name:TelegramBot}") String channelName,
                                                @Value("${imgbed.root-folder:blog}") String root) {
        return new CloudflareHostedAudioRepository(new ImgBedSettings(baseUrl, token, channel, channelName,
                root, Duration.ofSeconds(10)), json);
    }

    @Bean
    MusicMediaRepository musicMediaRepository(ObjectMapper json,
                                              @Value("${imgbed.base-url}") String baseUrl,
                                              @Value("${imgbed.api-token:}") String token,
                                              @Value("${imgbed.upload-channel:telegram}") String channel,
                                              @Value("${imgbed.channel-name:TelegramBot}") String channelName,
                                              @Value("${imgbed.root-folder:blog}") String root) {
        return new CloudflareMusicMediaRepository(new ImgBedSettings(baseUrl, token, channel, channelName,
                root, Duration.ofSeconds(30)), json);
    }

    @Bean
    MusicPolicy musicPolicy(MusicProperties properties) {
        return new MusicPolicy(properties.getPlaylistId());
    }
}
