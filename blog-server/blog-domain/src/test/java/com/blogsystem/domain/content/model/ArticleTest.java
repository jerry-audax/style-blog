package com.blogsystem.domain.content.model;

import com.blogsystem.domain.content.model.Article;
import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.ArticleStatus;
import com.blogsystem.domain.content.model.ContentBody;
import com.blogsystem.domain.content.model.ContentFormat;
import com.blogsystem.domain.content.model.UserId;

import org.assertj.core.api.Assertions;

import com.blogsystem.types.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleTest {

    @Test
    void publishingDraftEmitsEventAndRepeatedPublishIsRejected() {
        var article = Article.draft(
                new ArticleId(1L),
                new UserId(2L),
                "DDD",
                new ContentBody("<p>body</p>", ContentFormat.HTML),
                Instant.parse("2026-09-09T00:00:00Z"));

        var event = article.publish(Instant.parse("2026-09-09T01:00:00Z"));

        assertThat(article.status()).isEqualTo(ArticleStatus.PUBLISHED);
        assertThat(event.articleId()).isEqualTo(new ArticleId(1L));
        assertThatThrownBy(() -> article.publish(Instant.parse("2026-09-09T02:00:00Z")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void contentBodyRejectsBlankContent() {
        assertThatThrownBy(() -> new ContentBody("  ", ContentFormat.MARKDOWN))
                .isInstanceOf(DomainException.class);
    }
}
