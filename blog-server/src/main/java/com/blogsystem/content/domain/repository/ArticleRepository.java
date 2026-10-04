package com.blogsystem.content.domain.repository;

import com.blogsystem.content.domain.model.Article;
import com.blogsystem.content.domain.model.ArticleId;

import java.util.Optional;

public interface ArticleRepository {

    Optional<Article> findById(ArticleId id);

    Article save(Article article);

    void delete(ArticleId id);
}
