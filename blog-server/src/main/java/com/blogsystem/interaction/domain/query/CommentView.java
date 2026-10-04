package com.blogsystem.interaction.domain.query;

import java.time.LocalDateTime;

public record CommentView(Long id, Long articleId, Long userId, String nickname, String avatar,
                          Long parentId, Long replyToUserId, String replyToNickname,
                          String content, LocalDateTime createdAt) {
}

