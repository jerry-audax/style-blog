package com.blogsystem.domain.administration.query;

import com.blogsystem.domain.content.query.ArticleView;

import java.util.List;

public record DashboardSummary(
        long totalArticles,
        long publishedArticles,
        long totalComments,
        long pendingComments,
        long totalUsers,
        List<ArticleView> recentArticles) {
    public DashboardSummary {
        recentArticles = List.copyOf(recentArticles);
    }
}
