package com.blogsystem.content.domain.event;

import com.blogsystem.content.domain.model.ArticleId;
import com.blogsystem.content.domain.model.UserId;
import com.blogsystem.shared.domain.DomainEvent;

import java.time.Instant;

public record ArticleUnpublished(ArticleId articleId, UserId authorId, Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "content.article.unpublished";
    }
}
