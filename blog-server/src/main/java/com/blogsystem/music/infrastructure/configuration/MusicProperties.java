package com.blogsystem.music.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "music")
public class MusicProperties {
    private String node = "node";
    private String runtimeDirectory = "music-runtime";
    private String stateDirectory = "../data/music";
    private String playlistId = "939817038";

    public String getNode() {
        return node;
    }

    public void setNode(String value) {
        node = value;
    }

    public String getRuntimeDirectory() {
        return runtimeDirectory;
    }

    public void setRuntimeDirectory(String value) {
        runtimeDirectory = value;
    }

    public String getStateDirectory() {
        return stateDirectory;
    }

    public void setStateDirectory(String value) {
        stateDirectory = value;
    }

    public String getPlaylistId() {
        return playlistId;
    }

    public void setPlaylistId(String value) {
        playlistId = value;
    }

    public Path runtime() {
        return Path.of(runtimeDirectory).toAbsolutePath().normalize();
    }

    public Path state() {
        return Path.of(stateDirectory).toAbsolutePath().normalize();
    }
}
