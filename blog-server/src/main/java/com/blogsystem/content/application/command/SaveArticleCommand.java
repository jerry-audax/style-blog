package com.blogsystem.content.application.command;

import com.blogsystem.content.domain.model.ArticleRevision;

import java.util.Objects;

public record SaveArticleCommand(Long id, ArticleRevision revision) {
    public SaveArticleCommand {
        if (id != null && id <= 0) throw new IllegalArgumentException("文章 ID 无效");
        Objects.requireNonNull(revision, "revision");
    }
}

