package com.blogsystem.it.media;

import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.content.model.Article;
import com.blogsystem.domain.content.model.ArticleRevision;
import com.blogsystem.domain.content.model.ArticleStatus;
import com.blogsystem.domain.content.model.ContentBody;
import com.blogsystem.domain.content.model.ContentFormat;
import com.blogsystem.domain.content.model.UserId;
import com.blogsystem.infrastructure.adapter.repository.content.MybatisArticleRepository;
import com.blogsystem.infrastructure.rendering.content.ArticleBodyRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ArticleMediaWriteProtectionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final UUID OLD = UUID.fromString("00000000-0000-0000-0000-000000000821");
    private static final UUID NEXT = UUID.fromString("80000000-0000-0000-0000-000000000822");
    private static final UUID THIRD = UUID.fromString("90000000-0000-0000-0000-000000000823");
    private static final String OLD_URL = "/file/blog/images/old.png";
    private static final String NEXT_URL = "https://img.example/file/blog/images/中文+100%25.png";

    @ParameterizedTest @ValueSource(strings = {"```html\n<img\n```", "示例 `<img` 只显示代码", "# 示例\n\n    <img\n"})
    void markdownCodeExamplesCanBeSavedWithoutInventingMediaReferences(String markdown) throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            var draft = Article.newDraft(new UserId(7L), revision("代码示例", markdown, ContentFormat.MARKDOWN, null, List.of()), NOW);
            var saved = f.base.tx.execute(s -> f.articles.save(draft));
            String html = f.base.jdbc.queryForObject("SELECT content_html FROM article WHERE id=?", String.class, saved.id().value());
            assertTrue(html.contains("&lt;img"));
            assertTrue(org.jsoup.Jsoup.parse(html).select("img").isEmpty());
            assertEquals(0, f.base.trace.containing("from media_asset"));
        }
    }

    @Test void genuinelyUnclosedMarkupOutsideCodeStillRejectsArticleSave() throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            for (var format : List.of(ContentFormat.HTML, ContentFormat.MARKDOWN)) {
                String source = format == ContentFormat.HTML ? "    <img src='/file/blog/images/missing.png'" : "# 正文\n\n<img src='/file/blog/images/missing.png'";
                var draft = Article.newDraft(new UserId(7L), revision("真正不完整标签", source, format, null, List.of()), NOW);
                conflict(() -> f.base.tx.execute(s -> f.articles.save(draft)));
            }
            assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM article", Integer.class));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "DELETE_PENDING", "ACTIVE"})
    void indentedStoredHtmlCannotBypassResourceProtection(String state) throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            if (!state.equals("UNKNOWN")) {
                f.base.insert(OLD, "blog/images/old.png");
                f.base.jdbc.update("UPDATE media_asset SET status=? WHERE id=?", state, OLD);
            }
            String html = "    <img src='" + OLD_URL + "'>";
            var draft = Article.newDraft(new UserId(7L), revision("缩进HTML", html, ContentFormat.HTML, null, List.of()), NOW);
            f.base.trace.statements.clear();
            if (!state.equals("ACTIVE")) {
                conflict(() -> f.base.tx.execute(s -> f.articles.save(draft)));
                assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM article", Integer.class));
            } else {
                var saved = f.base.tx.execute(s -> f.articles.save(draft));
                assertEquals(html, f.base.jdbc.queryForObject("SELECT content_html FROM article WHERE id=?", String.class, saved.id().value()));
                assertTrue(f.base.trace.statements.stream().anyMatch(sql -> sql.contains("from media_asset") && sql.contains("for update")));
            }
        }
    }

    @Test void newAndEditedArticlesProtectStoredHtmlAndCover() throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            f.base.insert(OLD, "blog/images/old.png"); f.base.insert(NEXT, "blog/images/中文+100%.png");
            var draft = Article.newDraft(new UserId(7L), revision("原文", "![历史][cover]\n\n[cover]: " + OLD_URL, ContentFormat.MARKDOWN, OLD_URL, List.of(1L)), NOW);
            Article saved = f.base.tx.execute(s -> {
                var result = f.articles.save(draft); verifyNoInteractions(f.redis); return result;
            });
            verify(f.redis, times(2)).delete(anyString());
            assertTrue(f.base.jdbc.queryForObject("SELECT content_html FROM article WHERE id=?", String.class, saved.id().value()).contains("<img"));
            f.base.jdbc.update("UPDATE article SET view_count=31,like_count=7 WHERE id=?", saved.id().value());
            saved.revise(revision("编辑后", "<p>HTML</p><img srcset='" + NEXT_URL + " 1x, " + NEXT_URL + " 2x'>", ContentFormat.HTML, NEXT_URL, List.of(2L)), NOW.plusSeconds(1));
            var edited = f.base.tx.execute(s -> f.articles.save(saved));
            assertEquals(NEXT_URL, edited.revision().coverUrl());
            assertEquals(7L, edited.authorId().value());
            assertEquals(31, f.base.jdbc.queryForObject("SELECT view_count FROM article WHERE id=?", Integer.class, saved.id().value()));
            assertEquals(7, f.base.jdbc.queryForObject("SELECT like_count FROM article WHERE id=?", Integer.class, saved.id().value()));
            assertEquals(List.of(2L), f.base.jdbc.queryForList("SELECT tag_id FROM article_tag WHERE article_id=?", Long.class, saved.id().value()));
            f.base.jdbc.update("UPDATE media_asset SET status='DELETE_PENDING' WHERE id=?", NEXT);
            edited.revise(revision("不能保留失效封面", "纯文字", ContentFormat.MARKDOWN, NEXT_URL, List.of(2L)), NOW.plusSeconds(2));
            conflict(() -> f.base.tx.execute(s -> f.articles.save(edited)));
            assertEquals("编辑后", f.base.jdbc.queryForObject("SELECT title FROM article WHERE id=?", String.class, saved.id().value()));
        }
    }

    @Test void publishRevalidatesRetainedMedia() throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            f.base.insert(OLD, "blog/images/old.png"); var saved = create(f, OLD_URL, List.of(1L));
            clearInvocations(f.redis);
            f.base.jdbc.update("UPDATE media_asset SET status='DELETE_PENDING' WHERE id=?", OLD);
            conflict(() -> f.base.tx.execute(s -> f.commands.publish(saved.id(), NOW.plusSeconds(1))));
            assertEquals(0, f.base.jdbc.queryForObject("SELECT status FROM article WHERE id=?", Integer.class, saved.id().value()));
            assertTrue(f.events.isEmpty()); verifyNoInteractions(f.redis);
        }
    }

    @Test void failedProtectionRollsBackArticleTagsAndPublishEvent() throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            f.base.insert(OLD, "blog/images/old.png"); var saved = create(f, OLD_URL, List.of(1L));
            clearInvocations(f.redis);
            saved.revise(revision("不能保存", "<img src='/file/blog/images/missing.png'>", ContentFormat.HTML, OLD_URL, List.of(2L)), NOW.plusSeconds(1));
            conflict(() -> f.base.tx.execute(s -> f.articles.save(saved)));
            assertEquals("原文", f.base.jdbc.queryForObject("SELECT title FROM article WHERE id=?", String.class, saved.id().value()));
            assertEquals(List.of(1L), f.base.jdbc.queryForList("SELECT tag_id FROM article_tag WHERE article_id=?", Long.class, saved.id().value()));
            assertTrue(f.events.isEmpty()); verifyNoInteractions(f.redis);
            var badNew = Article.newDraft(new UserId(7L), revision("新建拒绝", "<img src='/file/blog/images/missing.png'>", ContentFormat.HTML, null, List.of(2L)), NOW);
            conflict(() -> f.base.tx.execute(s -> f.articles.save(badNew)));
            assertEquals(1, f.base.jdbc.queryForObject("SELECT count(*) FROM article", Integer.class));
        }
    }

    @Test void removingHistoricalUnknownLinksAndSavingExternalOrPlainTextRemainsPossible() throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            Long id = f.base.jdbc.queryForObject("INSERT INTO article(author_id,title,content_md,content_html,cover_url,created_at,updated_at) VALUES (7,'旧文','<img src=\"/file/blog/images/missing.png\">','<img src=\"/file/blog/images/missing.png\">','/file/blog/images/missing.png','2026-10-18 00:00:00','2026-10-18 00:00:00') RETURNING id", Long.class);
            var article = f.articles.findById(new com.blogsystem.domain.content.model.ArticleId(id)).orElseThrow();
            conflict(() -> f.base.tx.execute(s -> f.articles.save(article)));
            article.revise(revision("已修复", "纯文字", ContentFormat.MARKDOWN, "https://external.example/cover.png", List.of()), NOW.plusSeconds(1));
            assertDoesNotThrow(() -> f.base.tx.execute(s -> f.articles.save(article)));
            assertEquals("https://external.example/cover.png", f.base.jdbc.queryForObject("SELECT cover_url FROM article WHERE id=?", String.class, id));
            assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM media_asset", Integer.class));
        }
    }

    // Real rendering is retained; a deterministic renderer extension makes output-only URLs observable.
    @Test void actualRenderedHtmlIsValidatedRatherThanOnlyTheRawBody() throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            var renderer = new ArticleBodyRenderer() {
                @Override public String render(ContentBody body) { return super.render(body) + "<img src='/file/blog/images/rendered-only.png'>"; }
            };
            var repository = f.repository(f.base.guard, renderer);
            var draft = Article.newDraft(new UserId(7L), revision("渲染结果", "普通Markdown", ContentFormat.MARKDOWN, null, List.of()), NOW);
            conflict(() -> f.base.tx.execute(s -> repository.save(draft)));
            assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM article", Integer.class));
        }
    }

    @Test void laterTagFailureRollsBackProtectedArticleAndAfterCommitEffects() throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            f.base.insert(OLD, "blog/images/old.png"); var saved = create(f, OLD_URL, List.of(1L)); clearInvocations(f.redis);
            saved.revise(revision("回滚", "正文", ContentFormat.MARKDOWN, OLD_URL, List.of(999L)), NOW.plusSeconds(1));
            var failure = assertThrows(DataIntegrityViolationException.class, () -> f.base.tx.execute(s -> f.articles.save(saved)));
            assertEquals("23503", ((java.sql.SQLException) failure.getMostSpecificCause()).getSQLState());
            assertEquals("原文", f.base.jdbc.queryForObject("SELECT title FROM article WHERE id=?", String.class, saved.id().value()));
            assertEquals(List.of(1L), f.base.jdbc.queryForList("SELECT tag_id FROM article_tag WHERE article_id=?", Long.class, saved.id().value()));
            assertTrue(f.events.isEmpty()); verifyNoInteractions(f.redis);
        }
    }

    @Test @Timeout(30) void concurrentMediaChangeRejectsWithoutUnorderedRelock() throws Exception {
        try (var f = new ArticleMediaWriteFixture()) {
            f.base.insert(OLD, "blog/images/old.png"); f.base.insert(NEXT, "blog/images/中文+100%.png"); f.base.insert(THIRD, "blog/images/third.png");
            var initial = create(f, OLD_URL, List.of(1L)); clearInvocations(f.redis);
            var observed = new CountDownLatch(1); var changed = new CountDownLatch(1); var firstLocks = new ArrayList<UUID>();
            MediaReferenceWriteRepository actual = f.base.guard(new MediaDeletionAssetRepository() {
                @Override public java.util.Optional<MediaDeletionAssetSnapshot> findById(UUID id) { return f.base.locks.findById(id); }
                @Override public List<MediaDeletionAssetSnapshot> lockByIds(Set<UUID> ids) { var rows = f.base.locks.lockByIds(ids); rows.forEach(row -> firstLocks.add(row.asset().id())); return rows; }
                @Override public MediaDeletionAssetSnapshot confirmOwnership(UUID id, MediaDeletionOwnership ownership, long version, Instant now) { return f.base.locks.confirmOwnership(id, ownership, version, now); }
            });
            var delayed = f.repository((previous, desired) -> { observed.countDown(); await(changed); actual.lockForWrite(previous, desired); });
            var pool = Executors.newSingleThreadExecutor();
            try {
                var first = pool.submit(() -> assertThrows(MediaReferenceWriteException.class, () -> f.base.tx.execute(s -> {
                    var candidate = delayed.findById(initial.id()).orElseThrow();
                    candidate.revise(revision("迟到编辑", "正文", ContentFormat.MARKDOWN, "/file/blog/images/third.png", List.of(2L)), NOW.plusSeconds(2));
                    return delayed.save(candidate);
                })));
                assertTrue(observed.await(10, TimeUnit.SECONDS));
                f.base.tx.executeWithoutResult(s -> {
                    var newer = f.articles.findById(initial.id()).orElseThrow();
                    newer.revise(revision("先完成的编辑", "正文", ContentFormat.MARKDOWN, NEXT_URL, List.of(1L)), NOW.plusSeconds(1));
                    f.articles.save(newer);
                });
                changed.countDown(); assertEquals(MediaReferenceWriteException.Reason.CONFLICT, first.get(10, TimeUnit.SECONDS).reason());
                assertEquals(List.of(OLD, THIRD), firstLocks);
                assertEquals(NEXT_URL, f.base.jdbc.queryForObject("SELECT cover_url FROM article WHERE id=?", String.class, initial.id().value()));
                verify(f.redis, times(2)).delete(anyString());
            } finally { changed.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void springRepositoryProxyProvidesTheOriginalSaveTransaction() throws Exception {
        try (var f = new ArticleMediaWriteFixture(); var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfig.class);
            context.registerBean(DataSource.class, () -> f.base.dataSource);
            context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(f.base.dataSource));
            context.registerBean(MybatisArticleRepository.class, () -> f.repository(f.base.guard));
            context.refresh();
            var draft = Article.newDraft(new UserId(7L), revision("事务", "正文", ContentFormat.MARKDOWN, null, List.of()), NOW);
            assertDoesNotThrow(() -> context.getBean(MybatisArticleRepository.class).save(draft));
            assertEquals(1, f.base.jdbc.queryForObject("SELECT count(*) FROM article", Integer.class));
        }
    }

    private static Article create(ArticleMediaWriteFixture f, String cover, List<Long> tags) {
        return f.base.tx.execute(s -> f.articles.save(Article.newDraft(new UserId(7L), revision("原文", "正文", ContentFormat.MARKDOWN, cover, tags), NOW)));
    }
    private static ArticleRevision revision(String title, String text, ContentFormat format, String cover, List<Long> tags) {
        return new ArticleRevision(title, null, new ContentBody(text, format), cover, null, ArticleStatus.DRAFT, 0, 1, tags);
    }
    private static void conflict(Runnable action) { assertEquals(MediaReferenceWriteException.Reason.CONFLICT, assertThrows(MediaReferenceWriteException.class, action::run).reason()); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    @Configuration @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig {}
}
