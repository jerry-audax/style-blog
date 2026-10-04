package com.blogsystem.asset.infrastructure.configuration;

import java.net.URI;
import java.time.Duration;

/**
 * Do not use a generated toString: this object contains credentials.
 */
public final class ImgBedSettings {
    public final URI baseUrl;
    public final String token, uploadChannel, channelName, rootFolder;
    public final Duration timeout;

    public ImgBedSettings(String baseUrl, String token, String uploadChannel, String channelName, String rootFolder, Duration timeout) {
        this.baseUrl = URI.create(baseUrl);
        boolean localTest = "http".equals(this.baseUrl.getScheme()) && "127.0.0.1".equals(this.baseUrl.getHost());
        if ((!"https".equals(this.baseUrl.getScheme()) && !localTest) || this.baseUrl.getHost() == null ||
                this.baseUrl.getUserInfo() != null || this.baseUrl.getQuery() != null || this.baseUrl.getFragment() != null ||
                !this.baseUrl.getPath().matches("/?")) throw new IllegalArgumentException("Invalid ImgBed base URL");
        if (rootFolder == null || !rootFolder.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*"))
            throw new IllegalArgumentException("Invalid ImgBed root folder");
        if (!java.util.Set.of("telegram", "cfr2", "s3", "discord", "huggingface", "webdav").contains(uploadChannel))
            throw new IllegalArgumentException("Invalid ImgBed upload channel");
        if (token == null || token.contains("\r") || token.contains("\n"))
            throw new IllegalArgumentException("Invalid ImgBed token configuration");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toSeconds() > 60)
            throw new IllegalArgumentException("Invalid ImgBed timeout");
        this.token = token;
        this.uploadChannel = uploadChannel;
        this.channelName = channelName == null ? "" : channelName;
        this.rootFolder = rootFolder;
        this.timeout = timeout;
    }

    @Override
    public String toString() {
        return "ImgBedSettings[credentials redacted]";
    }
}
