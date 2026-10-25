package com.blogsystem.application.content.service;

import com.blogsystem.application.content.command.SaveArticleCommand;
import com.blogsystem.domain.content.model.Article;
import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.UserId;
import com.blogsystem.domain.content.adapter.repository.ArticleRepository;
import com.blogsystem.domain.shared.adapter.port.DomainEventPublisher;
import com.blogsystem.types.exception.DomainException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class ArticleCommandService {

    private final ArticleRepository articleRepository;
    private final DomainEventPublisher eventPublisher;

    public ArticleCommandService(
            ArticleRepository articleRepository, DomainEventPublisher eventPublisher) {
        this.articleRepository =
                Objects.requireNonNull(articleRepository, "articleRepository must not be null");
        this.eventPublisher =
                Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    @Transactional
    public Article save(SaveArticleCommand command, long authorId, Instant occurredAt) {
        Article article;
        if (command.id() == null) {
            article = Article.newDraft(new UserId(authorId), command.revision(), occurredAt);
        } else {
            article =
                    articleRepository
                            .findById(new ArticleId(command.id()))
                            .orElseThrow(() -> new IllegalArgumentException("文章不存在"));
            article.revise(command.revision(), occurredAt);
        }
        return articleRepository.save(article);
    }

    @Transactional
    public Article publish(ArticleId articleId, Instant occurredAt) {
        Article article =
                articleRepository
                        .findById(articleId)
                        .orElseThrow(() -> new DomainException("文章不存在"));
        var event = article.publish(occurredAt);
        Article saved = articleRepository.save(article);
        eventPublisher.publish(event);
        return saved;
    }
}
