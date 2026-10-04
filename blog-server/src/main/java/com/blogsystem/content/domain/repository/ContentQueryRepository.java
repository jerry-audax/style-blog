package com.blogsystem.content.domain.repository;

import com.blogsystem.content.domain.query.ArticleView;
import com.blogsystem.shared.domain.PageResult;

import java.util.List;
import java.util.Optional;

public interface ContentQueryRepository {
    PageResult<ArticleView> listArticles(Integer status, Long categoryId, Long tagId, long pageNum, long pageSize);

    List<ArticleView> listHotArticles(long limit);

    Optional<ArticleView> findArticle(Long id, boolean includeDrafts);

    int incrementViews(Long id, boolean includeDrafts);
}

