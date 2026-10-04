package com.blogsystem.interaction.domain.model;

import com.blogsystem.shared.domain.DomainException;

public record CommentTarget(Long articleId, boolean published, boolean commentsEnabled) {
    public void requireCommentable() {
        if (!published) throw new DomainException("文章未发布，无法评论");
        if (!commentsEnabled) throw new DomainException("文章已关闭评论");
    }
}
