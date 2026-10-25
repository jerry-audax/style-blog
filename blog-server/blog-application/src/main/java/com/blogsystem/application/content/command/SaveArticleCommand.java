package com.blogsystem.application.content.command;

import com.blogsystem.domain.content.model.ArticleRevision;

import java.util.Objects;

public record SaveArticleCommand(Long id, ArticleRevision revision) {
    public SaveArticleCommand {
        if (id != null && id <= 0) throw new IllegalArgumentException("文章 ID 无效");
        Objects.requireNonNull(revision, "revision");
    }
}
