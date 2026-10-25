package com.blogsystem.domain.content.adapter.repository;

public interface ArticleEngagementRepository {
    boolean toggleLike(Long articleId, long userId);
}
