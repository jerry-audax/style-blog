package com.blogsystem.content.domain.model;

import com.blogsystem.shared.domain.DomainException;

import java.util.List;
import java.util.Objects;

/**
 * Editable content only. The author, counters and creation date are not editable input.
 */
public record ArticleRevision(String title, String summary, ContentBody body, String coverUrl,
                              Long categoryId, ArticleStatus status, int isTop, int isCommentEnabled,
                              List<Long> tagIds) {
    public ArticleRevision {
        if (title == null || title.isBlank()) throw new DomainException("文章标题不能为空");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(status, "status");
        if ((isTop != 0 && isTop != 1) || (isCommentEnabled != 0 && isCommentEnabled != 1))
            throw new DomainException("文章开关状态无效");
        if (tagIds != null && tagIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new DomainException("标签 ID 无效");
        tagIds = tagIds == null ? List.of() : tagIds.stream().distinct().toList();
    }
}

