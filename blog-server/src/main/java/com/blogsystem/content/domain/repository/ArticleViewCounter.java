package com.blogsystem.content.domain.repository;

import com.blogsystem.content.domain.model.ArticleId;

public interface ArticleViewCounter {

    int increment(ArticleId id);
}
