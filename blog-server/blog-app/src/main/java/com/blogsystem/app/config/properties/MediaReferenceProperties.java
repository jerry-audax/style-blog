package com.blogsystem.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "media.catalog.references")
public final class MediaReferenceProperties {
    private List<String> allowedOrigins = new ArrayList<>();
    private int maxContentChars = 1048576;
    private int maxLinks = 10000;

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = new ArrayList<>(allowedOrigins); }
    public int getMaxContentChars() { return maxContentChars; }
    public void setMaxContentChars(int maxContentChars) { this.maxContentChars = maxContentChars; }
    public int getMaxLinks() { return maxLinks; }
    public void setMaxLinks(int maxLinks) { this.maxLinks = maxLinks; }
}
