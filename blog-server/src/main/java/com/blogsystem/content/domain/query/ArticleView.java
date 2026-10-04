package com.blogsystem.content.domain.query;

import java.time.LocalDateTime;
import java.util.List;

public record ArticleView(Long id, Long authorId, String title, String summary, String contentMd,
                          String contentHtml, String coverUrl, Long categoryId, Integer status,
                          Integer isTop, Integer isCommentEnabled, Integer viewCount, Integer likeCount,
                          Integer collectCount, LocalDateTime publishTime, LocalDateTime createdAt,
                          LocalDateTime updatedAt, Integer deleted, String categoryName, List<String> tagNames,
                          List<Long> tagIds) {
    public ArticleView {
        tagNames = tagNames == null ? null : List.copyOf(tagNames);
        tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
    }

    public boolean publiclyVisible() {
        return Integer.valueOf(1).equals(status) && Integer.valueOf(0).equals(deleted);
    }

    public ArticleView withViewCount(int count) {
        return new ArticleView(id, authorId, title, summary, contentMd, contentHtml, coverUrl, categoryId,
                status, isTop, isCommentEnabled, count, likeCount, collectCount, publishTime, createdAt,
                updatedAt, deleted, categoryName, tagNames, tagIds);
    }
}

