package com.blogsystem.domain.content.model;

import com.blogsystem.domain.content.model.Article;
import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.ArticleRevision;
import com.blogsystem.domain.content.model.ArticleStatus;
import com.blogsystem.domain.content.model.ContentBody;
import com.blogsystem.domain.content.model.ContentFormat;
import com.blogsystem.domain.content.model.UserId;

import java.util.ArrayList;
import java.util.Arrays;
import org.assertj.core.api.Assertions;

import com.blogsystem.types.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ArticleEditingTest {
    private final Instant first = Instant.parse("2026-10-03T00:00:00Z");

    private ArticleRevision revision(ArticleStatus status, List<Long> tags) {
        return new ArticleRevision("updated", "summary", new ContentBody("<p>body</p>", ContentFormat.HTML),
                "https://example.test/cover", 3L, status, 1, 0, tags);
    }

    @Test
    void creationWaitsForDatabaseIdentityAndKeepsMetadata() {
        var article = Article.newDraft(new UserId(42L), revision(ArticleStatus.PUBLISHED, List.of(1L, 1L, 2L)), first);
        assertThat(article.id()).isNull();
        assertThat(article.authorId().value()).isEqualTo(42L);
        assertThat(article.publishedAt()).isEqualTo(first);
        assertThat(article.revision().tagIds()).containsExactly(1L, 2L);
        assertThat(article.revision().isCommentEnabled()).isZero();
    }

    @Test
    void editingAndRepublishingKeepOriginalOwnerAndFirstPublication() {
        var article = Article.reconstitute(new ArticleId(1L), new UserId(42L),
                revision(ArticleStatus.PUBLISHED, List.of()), first, first, first);
        article.revise(revision(ArticleStatus.DRAFT, List.of()), first.plusSeconds(1));
        article.revise(revision(ArticleStatus.PUBLISHED, List.of()), first.plusSeconds(2));
        assertThat(article.authorId()).isEqualTo(new UserId(42L));
        assertThat(article.createdAt()).isEqualTo(first);
        assertThat(article.publishedAt()).isEqualTo(first);
        assertThat(article.updatedAt()).isEqualTo(first.plusSeconds(2));
    }

    @Test
    void switchesAndTagIdsMustBeValid() {
        assertThatThrownBy(() -> new ArticleRevision("title", null, new ContentBody("body", ContentFormat.MARKDOWN),
                null, null, ArticleStatus.DRAFT, 2, 1, List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> revision(ArticleStatus.DRAFT, List.of(0L))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> revision(ArticleStatus.DRAFT, java.util.Arrays.asList(1L, null))).isInstanceOf(DomainException.class);
    }

    @Test
    void tagsAreAnImmutableSnapshot() {
        var tags = new java.util.ArrayList<>(List.of(1L));
        var edit = revision(ArticleStatus.DRAFT, tags);
        tags.add(2L);
        assertThat(edit.tagIds()).containsExactly(1L);
        assertThatThrownBy(() -> edit.tagIds().add(3L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blankTitleCannotBypassHttpValidation() {
        assertThatThrownBy(() -> new ArticleRevision(" ", null, new ContentBody("body", ContentFormat.MARKDOWN),
                null, null, ArticleStatus.DRAFT, 0, 1, List.of())).isInstanceOf(DomainException.class);
    }
}
