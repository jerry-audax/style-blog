package com.blogsystem.trigger.http.interaction.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CommentSaveRequest(
        @NotNull(message = "文章ID不能为空") Long articleId,
        Long parentId,
        Long replyToUserId,
        @NotBlank(message = "评论内容不能为空") String content) {
    public com.blogsystem.domain.interaction.model.CommentSubmission toSubmission() {
        return new com.blogsystem.domain.interaction.model.CommentSubmission(
                articleId, parentId, replyToUserId, content);
    }
}
