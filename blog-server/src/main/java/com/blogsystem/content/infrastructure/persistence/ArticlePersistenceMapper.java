package com.blogsystem.content.infrastructure.persistence;

import com.blogsystem.content.domain.model.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public final class ArticlePersistenceMapper {
    private ArticlePersistenceMapper() {
    }

    public static com.blogsystem.content.domain.model.Article toDomain(Article row, List<Long> tags) {
        String source = row.getContentMd();
        if (source == null || source.isBlank()) source = row.getContentHtml();
        ContentFormat format = source != null && source.stripLeading().startsWith("<")
                ? ContentFormat.HTML : ContentFormat.MARKDOWN;
        Instant created = row.getCreatedAt() == null ? Instant.EPOCH : instant(row.getCreatedAt());
        Instant updated = row.getUpdatedAt() == null ? created : instant(row.getUpdatedAt());
        var revision = new ArticleRevision(row.getTitle(), row.getSummary(), new ContentBody(source, format),
                row.getCoverUrl(), row.getCategoryId(), ArticleStatus.fromLegacyValue(row.getStatus()),
                row.getIsTop() == null ? 0 : row.getIsTop(),
                row.getIsCommentEnabled() == null ? 1 : row.getIsCommentEnabled(), tags);
        return com.blogsystem.content.domain.model.Article.reconstitute(new ArticleId(row.getId()),
                new UserId(row.getAuthorId()), revision, instant(row.getPublishTime()), created, updated);
    }

    public static Article fromDomain(com.blogsystem.content.domain.model.Article aggregate, String renderedHtml) {
        Article row = new Article();
        var revision = aggregate.revision();
        row.setId(aggregate.id() == null ? null : aggregate.id().value());
        row.setAuthorId(aggregate.authorId().value());
        row.setTitle(revision.title());
        row.setSummary(revision.summary());
        row.setContentMd(revision.body().value());
        row.setContentHtml(renderedHtml);
        row.setCoverUrl(revision.coverUrl());
        row.setCategoryId(revision.categoryId());
        row.setStatus(revision.status().legacyValue());
        row.setIsTop(revision.isTop());
        row.setIsCommentEnabled(revision.isCommentEnabled());
        row.setPublishTime(local(aggregate.publishedAt()));
        row.setCreatedAt(local(aggregate.createdAt()));
        row.setUpdatedAt(local(aggregate.updatedAt()));
        return row;
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static LocalDateTime local(Instant time) {
        return time == null ? null : LocalDateTime.ofInstant(time, ZoneId.systemDefault());
    }
}
