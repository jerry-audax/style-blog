package com.blogsystem.ai.infrastructure.configuration;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Disabling the module must also prevent third-party starters from requiring keys/creating models.
 */
public class AiAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] candidates, AutoConfigurationMetadata metadata) {
        boolean localModels = environment != null && environment.getProperty("ai.enabled", Boolean.class, false)
                && "in-process".equals(environment.getProperty("ai.client.mode", "in-process"));
        boolean[] matches = new boolean[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            String type = candidates[i];
            boolean aiAutoConfiguration = type != null && (type.startsWith("org.springframework.ai.")
                    || type.startsWith("com.alibaba.cloud.ai."));
            matches[i] = localModels || !aiAutoConfiguration;
        }
        return matches;
    }
}
