package com.blogsystem.ai.domain.knowledge;

import java.time.Instant;

public record PublishedArticleSnapshot(Long articleId, String title, String content, Instant publishedAt) {
    public PublishedArticleSnapshot {
        if (articleId == null || articleId < 1) throw new IllegalArgumentException("articleId is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
        if (publishedAt == null) throw new IllegalArgumentException("publishedAt is required");
    }
}
