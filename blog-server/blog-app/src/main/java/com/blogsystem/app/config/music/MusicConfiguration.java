package com.blogsystem.app.config.music;

import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.application.music.service.MusicCoverBackfillApplicationService;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;
import com.blogsystem.domain.music.gateway.SongArtworkDownloadGateway;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.domain.music.gateway.HostedAudioGateway;
import com.blogsystem.domain.music.gateway.MusicMediaGateway;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.infrastructure.configuration.asset.ImgBedSettings;
import com.blogsystem.infrastructure.configuration.music.MusicProperties;
import com.blogsystem.infrastructure.gateway.imgbed.music.CloudflareHostedAudioRepository;
import com.blogsystem.infrastructure.gateway.imgbed.music.CloudflareMusicMediaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class MusicConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${media.catalog.storage:legacy-json}' == 'postgres' && ${media.catalog.uploads.enabled:false}")
    MusicCoverBackfillApplicationService musicCoverBackfillApplicationService(
            MusicTrackRepository tracks, SongArtworkDownloadGateway artwork,
            MediaUploadOperationRepository operations, MediaUploadApplicationService uploads) {
        return new MusicCoverBackfillApplicationService(tracks, artwork, operations, uploads);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "media.catalog.storage",
            havingValue = "legacy-json",
            matchIfMissing = true)
    HostedAudioGateway hostedAudioRepository(
            ObjectMapper json,
            @Value("${imgbed.base-url}") String baseUrl,
            @Value("${imgbed.api-token:}") String token,
            @Value("${imgbed.upload-channel:telegram}") String channel,
            @Value("${imgbed.channel-name:TelegramBot}") String channelName,
            @Value("${imgbed.root-folder:blog}") String root) {
        return new CloudflareHostedAudioRepository(
                new ImgBedSettings(
                        baseUrl, token, channel, channelName, root, Duration.ofSeconds(10)),
                json);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "media.catalog.storage",
            havingValue = "legacy-json",
            matchIfMissing = true)
    MusicMediaGateway musicMediaRepository(
            ObjectMapper json,
            @Value("${imgbed.base-url}") String baseUrl,
            @Value("${imgbed.api-token:}") String token,
            @Value("${imgbed.upload-channel:telegram}") String channel,
            @Value("${imgbed.channel-name:TelegramBot}") String channelName,
            @Value("${imgbed.root-folder:blog}") String root) {
        return new CloudflareMusicMediaRepository(
                new ImgBedSettings(
                        baseUrl, token, channel, channelName, root, Duration.ofSeconds(30)),
                json);
    }

    @Bean
    MusicPolicy musicPolicy(MusicProperties properties) {
        return new MusicPolicy(properties.getPlaylistId());
    }
}
