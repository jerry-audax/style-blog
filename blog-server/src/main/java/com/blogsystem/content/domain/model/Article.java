package com.blogsystem.content.domain.model;

import com.blogsystem.content.domain.event.ArticlePublished;
import com.blogsystem.content.domain.event.ArticleUnpublished;
import com.blogsystem.shared.domain.DomainException;

import java.time.Instant;
import java.util.Objects;

public final class Article {

    private final ArticleId id;
    private final UserId authorId;
    private String title;
    private ContentBody body;
    private String summary;
    private String coverUrl;
    private Long categoryId;
    private int isTop;
    private int isCommentEnabled = 1;
    private java.util.List<Long> tagIds = java.util.List.of();
    private final Instant createdAt;
    private ArticleStatus status;
    private Instant publishedAt;
    private Instant updatedAt;

    private Article(ArticleId id, UserId authorId, String title, ContentBody body, Instant createdAt) {
        this.id = id;
        this.authorId = Objects.requireNonNull(authorId, "author id must not be null");
        if (title == null || title.isBlank()) {
            throw new DomainException("文章标题不能为空");
        }
        this.title = title;
        this.body = Objects.requireNonNull(body, "article body must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = createdAt;
        this.status = ArticleStatus.DRAFT;
    }

    public static Article draft(ArticleId id, UserId authorId, String title, ContentBody body, Instant createdAt) {
        return new Article(Objects.requireNonNull(id, "article id must not be null"), authorId, title, body, createdAt);
    }

    public static Article newDraft(UserId authorId, ArticleRevision revision, Instant createdAt) {
        Article article = new Article(null, authorId, revision.title(), revision.body(), createdAt);
        article.revise(revision, createdAt);
        return article;
    }

    /**
     * Editing keeps original author and first publication date, matching existing save semantics.
     */
    public void revise(ArticleRevision revision, Instant occurredAt) {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(occurredAt, "occurredAt");
        title = revision.title();
        body = revision.body();
        summary = revision.summary();
        coverUrl = revision.coverUrl();
        categoryId = revision.categoryId();
        isTop = revision.isTop();
        isCommentEnabled = revision.isCommentEnabled();
        tagIds = revision.tagIds();
        status = revision.status();
        if (status == ArticleStatus.PUBLISHED && publishedAt == null) publishedAt = occurredAt;
        updatedAt = occurredAt;
    }

    public ArticleRevision revision() {
        return new ArticleRevision(title, summary, body, coverUrl, categoryId, status, isTop, isCommentEnabled, tagIds);
    }

    public static Article reconstitute(ArticleId id, UserId authorId, ArticleRevision revision,
                                       Instant publishedAt, Instant createdAt, Instant updatedAt) {
        Article article = new Article(Objects.requireNonNull(id), authorId, revision.title(), revision.body(), createdAt);
        article.revise(revision, updatedAt);
        article.publishedAt = publishedAt;
        return article;
    }

    public static Article reconstitute(ArticleId id, UserId authorId, String title, ContentBody body,
                                       ArticleStatus status, Instant publishedAt, Instant createdAt, Instant updatedAt) {
        Article article = new Article(id, authorId, title, body, createdAt);
        article.status = Objects.requireNonNull(status, "status must not be null");
        article.publishedAt = publishedAt;
        article.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        return article;
    }

    public ArticlePublished publish(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(id, "persist article before publishing an event");
        if (status != ArticleStatus.DRAFT) {
            throw new DomainException("文章已经发布");
        }
        status = ArticleStatus.PUBLISHED;
        publishedAt = occurredAt;
        updatedAt = occurredAt;
        return new ArticlePublished(id, authorId, occurredAt);
    }

    public ArticleUnpublished unpublish(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (status != ArticleStatus.PUBLISHED) {
            throw new DomainException("文章尚未发布");
        }
        status = ArticleStatus.DRAFT;
        publishedAt = null;
        updatedAt = occurredAt;
        return new ArticleUnpublished(id, authorId, occurredAt);
    }

    public ArticleId id() {
        return id;
    }

    public UserId authorId() {
        return authorId;
    }

    public String title() {
        return title;
    }

    public ContentBody body() {
        return body;
    }

    public ArticleStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
