package com.blogsystem.interaction.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CommentSaveRequest(
        @NotNull(message = "文章ID不能为空")
        Long articleId,
        Long parentId,
        Long replyToUserId,
        @NotBlank(message = "评论内容不能为空")
        String content
) {
    public com.blogsystem.interaction.domain.model.CommentSubmission toSubmission() {
        return new com.blogsystem.interaction.domain.model.CommentSubmission(articleId, parentId, replyToUserId, content);
    }
}
