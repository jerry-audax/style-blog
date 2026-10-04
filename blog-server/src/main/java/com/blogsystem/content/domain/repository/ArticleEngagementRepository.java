package com.blogsystem.content.domain.repository;

public interface ArticleEngagementRepository {
    boolean toggleLike(Long articleId, long userId);
}

