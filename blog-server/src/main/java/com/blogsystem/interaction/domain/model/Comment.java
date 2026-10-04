package com.blogsystem.interaction.domain.model;

import com.blogsystem.shared.domain.DomainException;

import java.time.Instant;
import java.util.Objects;

public final class Comment {
    private final Long id;
    private final Long articleId;
    private final Long authorId;
    private final String content;
    private final Instant createdAt;
    private CommentStatus status;
    private Long parentId = 0L;
    private Long replyToUserId;
    private CommentOrigin origin = new CommentOrigin(null, null);

    private Comment(Long id, Long articleId, Long authorId, String content, Instant createdAt) {
        this.id = id;
        this.articleId = Objects.requireNonNull(articleId);
        this.authorId = Objects.requireNonNull(authorId);
        if (content == null || content.isBlank()) throw new DomainException("评论内容不能为空");
        this.content = content;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = CommentStatus.PENDING;
    }

    public static Comment create(Long articleId, Long authorId, String content, Instant createdAt) {
        return new Comment(null, articleId, authorId, content, createdAt);
    }

    public static Comment submit(CommentSubmission submission, Long authorId, CommentOrigin origin, Instant createdAt) {
        Comment comment = create(submission.articleId(), authorId, submission.content(), createdAt);
        comment.parentId = submission.parentId();
        comment.replyToUserId = submission.replyToUserId();
        comment.origin = Objects.requireNonNull(origin);
        return comment;
    }

    public static Comment reconstitute(Long id, CommentSubmission submission, Long authorId,
                                       CommentStatus status, CommentOrigin origin, Instant createdAt) {
        Comment comment = reconstitute(id, submission.articleId(), authorId, submission.content(), status, createdAt);
        comment.parentId = submission.parentId();
        comment.replyToUserId = submission.replyToUserId();
        comment.origin = Objects.requireNonNull(origin);
        return comment;
    }

    public void requireValidParent(Comment parent) {
        if (parent == null || !Objects.equals(parentId, parent.id())) throw new DomainException("父评论不存在");
        if (!articleId.equals(parent.articleId())) throw new DomainException("父评论不属于该文章");
    }

    public Long parentId() {
        return parentId;
    }

    public Long replyToUserId() {
        return replyToUserId;
    }

    public CommentOrigin origin() {
        return origin;
    }

    /**
     * Rebuild an existing aggregate from a repository record.
     */
    public static Comment reconstitute(Long id, Long articleId, Long authorId, String content,
                                       CommentStatus status, Instant createdAt) {
        Comment comment = new Comment(id, articleId, authorId, content, createdAt);
        if (status != null && status != CommentStatus.PENDING) {
            comment.audit(status);
        }
        return comment;
    }

    public void audit(CommentStatus target) {
        if (target == null || target == CommentStatus.PENDING) throw new DomainException("审核状态无效");
        status = target;
    }

    public Long id() {
        return id;
    }

    public Long articleId() {
        return articleId;
    }

    public Long authorId() {
        return authorId;
    }

    public String content() {
        return content;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public CommentStatus status() {
        return status;
    }
}
