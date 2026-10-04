package com.blogsystem.interaction.domain.query;

import java.time.LocalDateTime;

public record ModerationCommentView(Long id, Long articleId, Long userId, Long parentId, Long replyToUserId,
                                    String content, Integer status, String ip, String userAgent,
                                    LocalDateTime createdAt, LocalDateTime updatedAt, Integer deleted) {
}

