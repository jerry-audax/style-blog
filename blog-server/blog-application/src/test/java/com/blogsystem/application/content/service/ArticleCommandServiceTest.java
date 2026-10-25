package com.blogsystem.application.content.service;

import com.blogsystem.domain.content.event.ArticlePublished;
import com.blogsystem.domain.content.model.Article;
import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.ArticleStatus;
import com.blogsystem.domain.content.model.ContentBody;
import com.blogsystem.domain.content.model.ContentFormat;
import com.blogsystem.domain.content.model.UserId;
import com.blogsystem.domain.content.adapter.repository.ArticleRepository;
import com.blogsystem.domain.shared.adapter.port.DomainEventPublisher;
import com.blogsystem.domain.shared.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleCommandServiceTest {

    @Test
    void publishDelegatesToAggregateAndPersistsEvent() {
        var repository = new InMemoryArticleRepository(existingDraft());
        var events = new RecordingPublisher();
        var service = new ArticleCommandService(repository, events);

        service.publish(new ArticleId(1L), Instant.parse("2026-09-09T01:00:00Z"));

        assertThat(repository.findById(new ArticleId(1L)).orElseThrow().status())
                .isEqualTo(ArticleStatus.PUBLISHED);
        assertThat(events.events()).hasSize(1);
        assertThat(events.events().get(0)).isInstanceOf(ArticlePublished.class);
    }

    private static Article existingDraft() {
        return Article.draft(new ArticleId(1L), new UserId(2L), "DDD",
                new ContentBody("body", ContentFormat.MARKDOWN),
                Instant.parse("2026-09-09T00:00:00Z"));
    }

    private static final class InMemoryArticleRepository implements ArticleRepository {
        private final Map<ArticleId, Article> articles = new HashMap<>();

        private InMemoryArticleRepository(Article article) {
            articles.put(article.id(), article);
        }

        @Override
        public Optional<Article> findById(ArticleId id) {
            return Optional.ofNullable(articles.get(id));
        }

        @Override
        public Article save(Article article) {
            articles.put(article.id(), article);
            return article;
        }

        @Override
        public void delete(ArticleId id) {
            articles.remove(id);
        }
    }

    private static final class RecordingPublisher implements DomainEventPublisher {
        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }

        private List<DomainEvent> events() {
            return events;
        }
    }
}
