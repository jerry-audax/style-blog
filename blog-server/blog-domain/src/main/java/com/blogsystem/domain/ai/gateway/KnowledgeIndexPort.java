package com.blogsystem.domain.ai.gateway;

public interface KnowledgeIndexPort {
    void initializeOnStartup();

    int rebuild();
}
