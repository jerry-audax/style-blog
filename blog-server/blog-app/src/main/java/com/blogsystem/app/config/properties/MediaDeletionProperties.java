package com.blogsystem.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "media.catalog.deletions")
public final class MediaDeletionProperties {
    private boolean enabled;
    private String environmentId;
    private final PhysicalDelete physicalDelete = new PhysicalDelete();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public PhysicalDelete getPhysicalDelete() {
        return physicalDelete;
    }

    public static final class PhysicalDelete {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
