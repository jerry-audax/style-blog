package com.blogsystem.infrastructure.mybatis.converter.content;

import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.ArticleRevision;
import com.blogsystem.domain.content.model.ArticleStatus;
import com.blogsystem.domain.content.model.ContentBody;
import com.blogsystem.domain.content.model.ContentFormat;
import com.blogsystem.domain.content.model.UserId;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public final class ArticleConverter {
    private ArticleConverter() {}

    public static com.blogsystem.domain.content.model.Article toDomain(
            ArticleRecord row, List<Long> tags) {
        String source = row.getContentMd();
        if (source == null || source.isBlank()) source = row.getContentHtml();
        ContentFormat format =
                source != null && source.stripLeading().startsWith("<")
                        ? ContentFormat.HTML
                        : ContentFormat.MARKDOWN;
        Instant created = row.getCreatedAt() == null ? Instant.EPOCH : instant(row.getCreatedAt());
        Instant updated = row.getUpdatedAt() == null ? created : instant(row.getUpdatedAt());
        var revision =
                new ArticleRevision(
                        row.getTitle(),
                        row.getSummary(),
                        new ContentBody(source, format),
                        row.getCoverUrl(),
                        row.getCategoryId(),
                        ArticleStatus.fromLegacyValue(row.getStatus()),
                        row.getIsTop() == null ? 0 : row.getIsTop(),
                        row.getIsCommentEnabled() == null ? 1 : row.getIsCommentEnabled(),
                        tags);
        return com.blogsystem.domain.content.model.Article.reconstitute(
                new ArticleId(row.getId()),
                new UserId(row.getAuthorId()),
                revision,
                instant(row.getPublishTime()),
                created,
                updated);
    }

    public static ArticleRecord fromDomain(
            com.blogsystem.domain.content.model.Article aggregate, String renderedHtml) {
        ArticleRecord row = new ArticleRecord();
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
