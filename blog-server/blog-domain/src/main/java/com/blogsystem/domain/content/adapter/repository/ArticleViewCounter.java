package com.blogsystem.domain.content.adapter.repository;

import com.blogsystem.domain.content.model.ArticleId;

public interface ArticleViewCounter {

    int increment(ArticleId id);
}
