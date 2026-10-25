package com.blogsystem.infrastructure.configuration.asset;

import java.net.URI;
import java.time.Duration;

/** Independent tracked-upload timeouts; never widens the legacy ImgBed timeout. */
public final class MediaUploadProviderSettings {
    public final URI baseUrl;
    public final String token, uploadChannel, channelName, rootFolder;
    public final Duration directTimeout, initTimeout, chunkTimeout, mergeTimeout;

    public MediaUploadProviderSettings(
            String baseUrl,
            String token,
            String uploadChannel,
            String channelName,
            String rootFolder,
            Duration directTimeout,
            Duration initTimeout,
            Duration chunkTimeout,
            Duration mergeTimeout) {
        var validated =
                new ImgBedSettings(
                        baseUrl,
                        token,
                        uploadChannel,
                        channelName,
                        rootFolder,
                        Duration.ofSeconds(30));
        if (!"telegram".equals(uploadChannel)
                || channelName == null
                || channelName.isBlank()
                || channelName.length() > 128
                || channelName.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(
                    "Tracked upload requires a configured Telegram channel");
        for (Duration d : new Duration[] {directTimeout, initTimeout, chunkTimeout, mergeTimeout})
            if (d == null
                    || d.isZero()
                    || d.isNegative()
                    || d.compareTo(Duration.ofSeconds(120)) > 0)
                throw new IllegalArgumentException("Invalid upload timeout");
        this.baseUrl = validated.baseUrl;
        this.token = validated.token;
        this.uploadChannel = uploadChannel;
        this.channelName = channelName;
        this.rootFolder = rootFolder;
        this.directTimeout = directTimeout;
        this.initTimeout = initTimeout;
        this.chunkTimeout = chunkTimeout;
        this.mergeTimeout = mergeTimeout;
    }

    @Override
    public String toString() {
        return "MediaUploadProviderSettings[credentials redacted]";
    }
}
