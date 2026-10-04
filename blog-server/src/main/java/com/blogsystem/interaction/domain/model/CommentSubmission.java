package com.blogsystem.interaction.domain.model;

import com.blogsystem.shared.domain.DomainException;

public record CommentSubmission(Long articleId, Long parentId, Long replyToUserId, String content) {
    public CommentSubmission {
        if (articleId == null || articleId <= 0) throw new DomainException("文章 ID 无效");
        if (content == null || content.isBlank()) throw new DomainException("评论内容不能为空");
        parentId = parentId == null ? 0L : parentId;
        if (parentId < 0) throw new DomainException("父评论 ID 无效");
    }
}

