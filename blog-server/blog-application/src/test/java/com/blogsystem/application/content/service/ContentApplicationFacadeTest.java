package com.blogsystem.application.content.service;

import com.blogsystem.domain.content.model.Article;
import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.ArticleRevision;
import com.blogsystem.domain.content.model.ArticleStatus;
import com.blogsystem.domain.content.model.ContentBody;
import com.blogsystem.domain.content.model.ContentFormat;
import com.blogsystem.domain.content.adapter.repository.ArticleEngagementRepository;
import com.blogsystem.domain.content.adapter.repository.ArticleRepository;
import com.blogsystem.domain.content.adapter.repository.ContentQueryRepository;
import com.blogsystem.domain.content.adapter.repository.TaxonomyRepository;

import java.util.List;
import java.util.Optional;

import com.blogsystem.application.content.command.SaveArticleCommand;
import com.blogsystem.domain.content.query.ArticleView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentApplicationFacadeTest {
    ContentQueryRepository queries;
    ArticleRepository articles;
    TaxonomyRepository taxonomy;
    ContentApplicationFacade service;

    @BeforeEach
    void setup() {
        queries = mock(ContentQueryRepository.class);
        articles = mock(ArticleRepository.class);
        taxonomy = mock(TaxonomyRepository.class);
        service = new ContentApplicationFacade(new ArticleCommandService(articles, event -> {
        }), articles, queries,
                taxonomy, mock(ArticleEngagementRepository.class), () -> 42,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test void homepageSelectionUsesTheTagRepositoryAndReturnsTheBusinessFallback() {
        var tags = List.of(
                new com.blogsystem.domain.content.query.TagView(1L,"one","one",null,0,1,null,null,0,1),
                new com.blogsystem.domain.content.query.TagView(2L,"two","two",null,0,1,null,null,0,0));
        when(taxonomy.listTag()).thenReturn(tags);
        assertThat(service.listHomeTags()).extracting(com.blogsystem.domain.content.query.TagView::id).containsExactly(2L,1L);
        verify(taxonomy).listTag();
        when(taxonomy.listTag()).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(service::listHomeTags).isInstanceOf(IllegalStateException.class);
    }

    ArticleView view(int status, int deleted) {
        return new ArticleView(1L, 42L, "test", null, "body", "<p>body</p>", null, null, status, 0, 1,
                10, 0, 0, null, null, null, deleted, null, List.of(), List.of());
    }

    @Test
    void draftIsRejectedBeforeViewCountWriteEvenIfQueryAdapterLeaksIt() {
        when(queries.findArticle(1L, false)).thenReturn(Optional.of(view(0, 0)));
        assertThatThrownBy(() -> service.getArticle(1L, false)).isInstanceOf(IllegalArgumentException.class);
        verify(queries, never()).incrementViews(any(), anyBoolean());
    }

    @Test
    void deletedArticleIsRejectedForOwnerToo() {
        when(queries.findArticle(1L, true)).thenReturn(Optional.of(view(1, 1)));
        assertThatThrownBy(() -> service.getArticle(1L, true)).isInstanceOf(IllegalArgumentException.class);
        verify(queries, never()).incrementViews(any(), anyBoolean());
    }

    @Test
    void publishedReadIncrementsUsingRepositoryResult() {
        when(queries.findArticle(1L, false)).thenReturn(Optional.of(view(1, 0)));
        when(queries.incrementViews(1L, false)).thenReturn(12);
        assertThat(service.getArticle(1L, false).viewCount()).isEqualTo(12);
    }

    @Test
    void ownerCanPreviewDraftWithoutMakingItPublic() {
        when(queries.findArticle(1L, true)).thenReturn(Optional.of(view(0, 0)));
        when(queries.incrementViews(1L, true)).thenReturn(11);
        assertThat(service.getArticle(1L, true).status()).isZero();
    }

    @Test
    void hotQueryIsBoundedAndFiltersIneligibleRecords() {
        when(queries.listHotArticles(100)).thenReturn(List.of(view(0, 0), view(1, 1), view(1, 0)));
        assertThat(service.listHotArticles(Long.MAX_VALUE)).hasSize(1);
        verify(queries).listHotArticles(100);
    }

    @Test
    void savingNewArticleUsesServerIdentityAndRepositoryGeneratedKey() {
        var revision = new ArticleRevision("test", null, new ContentBody("body", ContentFormat.MARKDOWN),
                null, null, ArticleStatus.DRAFT, 0, 1, List.of());
        when(articles.save(any())).thenAnswer(call -> {
            Article draft = call.getArgument(0);
            assertThat(draft.id()).isNull();
            assertThat(draft.authorId().value()).isEqualTo(42L);
            return Article.reconstitute(new ArticleId(7L), draft.authorId(), draft.revision(), draft.publishedAt(),
                    draft.createdAt(), draft.updatedAt());
        });
        assertThat(service.saveArticle(new SaveArticleCommand(null, revision))).isEqualTo(7L);
    }

    @Test
    void editingMissingArticleDoesNotInsertOrPublish() {
        when(articles.findById(new ArticleId(99L))).thenReturn(Optional.empty());
        var revision = new ArticleRevision("test", null, new ContentBody("body", ContentFormat.MARKDOWN),
                null, null, ArticleStatus.DRAFT, 0, 1, List.of());
        assertThatThrownBy(() -> service.saveArticle(new SaveArticleCommand(99L, revision))).isInstanceOf(IllegalArgumentException.class);
        verify(articles, never()).save(any());
    }
}
