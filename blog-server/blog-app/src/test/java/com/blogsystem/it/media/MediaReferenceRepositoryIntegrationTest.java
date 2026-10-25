package com.blogsystem.it.media;

import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import com.blogsystem.domain.asset.reference.model.MediaReferenceQueryException;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence.Source;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence.Usage;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaReferenceRepositoryIntegrationTest {
    @Test void actualIndentedHtmlIsNotReinterpretedAsMarkdownCodeDuringPreview() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE);
            fixture.article(1, null, null, "    <img src='" + fixture.url(image) + "'>");
            var evidence = fixture.references.inspect(image);
            assertTrue(evidence.usages().contains(new Usage(Source.ARTICLE, "1", "文章正文")));
            assertTrue(evidence.successfulSources().contains(Source.ARTICLE));
        }
    }
    @Test void disabledButNotDeletedMusicAndVideoKeepTheirExactBusinessIdentity() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            fixture.jdbc.update("UPDATE music_track SET enabled=false"); fixture.jdbc.update("UPDATE surprise_video SET enabled=false");
            var audio = fixture.references.inspect(fixture.asset(MediaRuntimeRepositoryIntegrationTest.AUDIO));
            var video = fixture.references.inspect(fixture.asset(MediaRuntimeRepositoryIntegrationTest.VIDEO));
            assertTrue(audio.usages().contains(new Usage(Source.MUSIC, "  Existing-视频-ID  ", "歌曲音频")));
            assertTrue(video.usages().contains(new Usage(Source.SURPRISE, "video-1", "惊喜视频")));
            assertEquals(EnumSet.allOf(Source.class), audio.requiredSources());
            assertEquals(Set.of(Source.PUBLICATION), audio.failedSources()); assertFalse(audio.complete());
        }
    }

    @Test void draftHtmlLegacyMarkdownAvatarAndManagedExternalCoverAreAllMatchedWithoutLeakingContent() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE); String url = fixture.url(image);
            fixture.jdbc.update("UPDATE music_track SET external_cover_url=?", url);
            fixture.article(1, url, "<p>Tiptap HTML</p><img src='" + url + "'>", "<img src='" + url + "'>");
            fixture.article(2, null, "![历史][cover]\n\n[cover]: <" + url + ">", null);
            fixture.owner(7, url);
            var pin = new MediaPublicationPinKey("production", "hexo", "r1");
            fixture.base.service.prepare(pin, "a".repeat(64), Set.of(image.id()));
            var evidence = fixture.references.inspect(image);
            assertEquals(List.of(new Usage(Source.MUSIC, "  Existing-视频-ID  ", "歌曲封面"),
                    new Usage(Source.ARTICLE, "1", "文章封面"), new Usage(Source.ARTICLE, "1", "文章正文"),
                    new Usage(Source.ARTICLE, "2", "文章正文"), new Usage(Source.OWNER, "7", "账号头像"),
                    new Usage(Source.PUBLICATION, "production/hexo/r1", "发布版本")), evidence.usages());
            assertEquals(Set.of(Source.MUSIC, Source.SURPRISE, Source.ARTICLE, Source.OWNER), evidence.successfulSources());
            assertEquals(Set.of(Source.PUBLICATION), evidence.failedSources()); assertFalse(evidence.complete());
            assertFalse(evidence.toString().contains("private title"));
            assertFalse(evidence.toString().contains("test-only-not-a-real-secret"));
            assertFalse(evidence.toString().contains("https://"));
        }
    }

    @Test void onlySoftDeletedBusinessesAreExcludedAndInspectDoesNotChangeMedia() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE); String url = fixture.url(image);
            fixture.article(1, url, null, null); fixture.owner(1, url);
            assertEquals(3, fixture.references.inspect(image).usages().size());
            fixture.jdbc.update("UPDATE music_track SET deleted_at=now()"); fixture.jdbc.update("UPDATE surprise_video SET deleted_at=now()");
            fixture.jdbc.update("UPDATE article SET deleted=1"); fixture.jdbc.update("UPDATE sys_user SET deleted=1");
            assertTrue(fixture.references.inspect(image).usages().isEmpty());
            assertEquals(image, fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE));
            assertEquals(3, fixture.jdbc.queryForObject("SELECT count(*) FROM media_asset", Integer.class));
            assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM media_delete_operation", Integer.class));
        }
    }

    @Test void laterPagesAndMultipleSlotsAreNotLostOrFilteredByPublicQueries() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE); String url = fixture.url(image);
            for (int i = 1; i <= 205; i++) fixture.article(i, url, null, null);
            // Historical FK corruption must remain visible rather than disappearing through a kind/public filter.
            fixture.jdbc.update("UPDATE music_track SET audio_asset_id=?,lyrics_asset_id=?,enabled=false", image.id(), image.id());
            var evidence = fixture.references.inspect(image);
            assertEquals(205L, evidence.usages().stream().filter(usage -> usage.source() == Source.ARTICLE).count());
            assertEquals(3L, evidence.usages().stream().filter(usage -> usage.source() == Source.MUSIC).count());
            assertTrue(evidence.usages().contains(new Usage(Source.ARTICLE, "205", "文章封面")));
        }
    }

    @Test void malformedUnrelatedManagedLinkMakesSourceIncompleteWithoutErasingPositiveUsage() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE);
            fixture.article(1, fixture.url(image), "<img src='/file/blog/%252e%252e/not-this-file.png'>", null);
            var evidence = fixture.references.inspect(image);
            assertTrue(evidence.usages().contains(new Usage(Source.ARTICLE, "1", "文章封面")));
            assertTrue(evidence.usages().contains(new Usage(Source.MUSIC, "  Existing-视频-ID  ", "歌曲封面")));
            assertTrue(evidence.failedSources().contains(Source.ARTICLE)); assertFalse(evidence.successfulSources().contains(Source.ARTICLE));
            assertFalse(evidence.complete());
        }
    }

    @Test void failedArticleQueryOutsideTransactionPreservesMusicOwnerAndPublicationPositives() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE); fixture.owner(7, fixture.url(image));
            fixture.base.service.prepare(new MediaPublicationPinKey("local", "hexo", "r1"), "a".repeat(64), Set.of(image.id()));
            fixture.base.schema.execute("DROP TABLE article");
            var evidence = fixture.references.inspect(image);
            assertEquals(Set.of(Source.ARTICLE, Source.PUBLICATION), evidence.failedSources());
            assertTrue(evidence.usages().contains(new Usage(Source.MUSIC, "  Existing-视频-ID  ", "歌曲封面")));
            assertTrue(evidence.usages().contains(new Usage(Source.OWNER, "7", "账号头像")));
            assertTrue(evidence.usages().contains(new Usage(Source.PUBLICATION, "local/hexo/r1", "发布版本")));
        }
    }

    @Test void missingPublicationTableDoesNotTurnOtherSourcesIntoEmptyResults() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE); fixture.article(1, fixture.url(image), null, null);
            fixture.base.schema.execute("DROP TABLE media_publication_pin");
            var evidence = fixture.references.inspect(image);
            assertEquals(Set.of(Source.PUBLICATION), evidence.failedSources());
            assertTrue(evidence.usages().contains(new Usage(Source.ARTICLE, "1", "文章封面")));
            assertTrue(evidence.usages().contains(new Usage(Source.MUSIC, "  Existing-视频-ID  ", "歌曲封面")));
            assertFalse(evidence.complete());
        }
    }

    @Test void sqlFailureInsideAnExistingTransactionRollsBackRatherThanContinuingAnAbortedConnection() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            var image = fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE); fixture.base.schema.execute("DROP TABLE article");
            var failure = assertThrows(MediaReferenceQueryException.class, () -> fixture.base.tx.execute(status -> {
                fixture.jdbc.update("UPDATE media_asset SET version=version+1 WHERE id=?", image.id());
                return fixture.references.inspect(image);
            }));
            assertEquals(MediaReferenceQueryException.Reason.UNAVAILABLE, failure.reason()); assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("SELECT")); assertEquals(image, fixture.asset(MediaRuntimeRepositoryIntegrationTest.IMAGE));
        }
    }

    @Test void publicationPinsFromAllEnvironmentsArePositiveButDoNotProvePublisherCoverage() throws Exception {
        try (var fixture = new MediaReferenceWorkflowFixture()) {
            var audio = fixture.asset(MediaRuntimeRepositoryIntegrationTest.AUDIO);
            var local = new MediaPublicationPinKey("local", "hexo", "r1"); var production = new MediaPublicationPinKey("production", "hexo", "r1");
            for (var key : List.of(local, production)) fixture.base.service.prepare(key, "a".repeat(64), Set.of(audio.id()));
            var before = fixture.references.inspect(audio);
            assertEquals(2L, before.usages().stream().filter(usage -> usage.source() == Source.PUBLICATION).count());
            fixture.base.service.release(local, 0, "a".repeat(64));
            var after = fixture.references.inspect(audio);
            assertEquals(List.of(new Usage(Source.PUBLICATION, "production/hexo/r1", "发布版本")),
                    after.usages().stream().filter(usage -> usage.source() == Source.PUBLICATION).toList());
            assertFalse(after.complete()); assertTrue(after.failedSources().contains(Source.PUBLICATION));
        }
    }
}
