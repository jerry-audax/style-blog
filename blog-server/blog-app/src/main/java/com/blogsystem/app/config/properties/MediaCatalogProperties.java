package com.blogsystem.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "media.catalog")
public final class MediaCatalogProperties {
    private String storage = "legacy-json";
    private final Uploads uploads = new Uploads();

    public Uploads getUploads() {
        return uploads;
    }

    public static final class Uploads {
        private boolean enabled;

        public boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public String getStorage() {
        return storage;
    }

    public void setStorage(String value) {
        if (!"legacy-json".equals(value) && !"postgres".equals(value))
            throw new IllegalArgumentException("Invalid media catalogue storage mode");
        storage = value;
    }
}
