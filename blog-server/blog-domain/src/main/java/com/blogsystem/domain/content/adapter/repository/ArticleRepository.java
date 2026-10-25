package com.blogsystem.domain.content.adapter.repository;

import com.blogsystem.domain.content.model.Article;
import com.blogsystem.domain.content.model.ArticleId;

import java.util.Optional;

public interface ArticleRepository {

    Optional<Article> findById(ArticleId id);

    Article save(Article article);

    void delete(ArticleId id);
}
