package com.blogsystem.domain.content.event;

import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.UserId;
import com.blogsystem.domain.shared.DomainEvent;

import java.time.Instant;

public record ArticlePublished(ArticleId articleId, UserId authorId, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String eventType() {
        return "content.article.published";
    }
}
