package com.blogsystem.surprise.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "surprise")
public class SurpriseProperties {
    private String stateDirectory = "../data/music";
    public String getStateDirectory() { return stateDirectory; }
    public void setStateDirectory(String value) { stateDirectory = value; }
    public Path state() { return Path.of(stateDirectory).toAbsolutePath().normalize(); }
}
