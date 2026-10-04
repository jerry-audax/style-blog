package com.blogsystem.content.domain.repository;

import com.blogsystem.content.domain.model.ArticleId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PublishedArticlePort {

    Optional<PublishedArticleSnapshot> findPublished(ArticleId id);

    List<PublishedArticleSnapshot> listPublishedSnapshots();

    record PublishedArticleSnapshot(ArticleId id, String title, String content, Instant publishedAt) {
    }
}
