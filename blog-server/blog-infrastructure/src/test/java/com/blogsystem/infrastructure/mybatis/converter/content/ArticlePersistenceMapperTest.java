package com.blogsystem.infrastructure.mybatis.converter.content;

import com.blogsystem.infrastructure.mybatis.converter.content.ArticleConverter;

import com.blogsystem.domain.content.model.Article;
import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.ContentBody;
import com.blogsystem.domain.content.model.ContentFormat;
import com.blogsystem.domain.content.model.UserId;

import org.assertj.core.api.Assertions;

import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticlePersistenceMapperTest {
    @Test
    void draftRecordRoundTripsWithoutDomainAnnotations() {
        var aggregate = com.blogsystem.domain.content.model.Article.draft(new ArticleId(7L), new UserId(9L),
                "DDD", new ContentBody("# body", ContentFormat.MARKDOWN), Instant.parse("2026-09-09T00:00:00Z"));
        var row = ArticleConverter.fromDomain(aggregate, "<h1>body</h1>");
        var restored = ArticleConverter.toDomain(row, List.of());
        assertThat(row.getStatus()).isEqualTo(0);
        assertThat(restored.id()).isEqualTo(new ArticleId(7L));
        assertThat(restored.body().format()).isEqualTo(ContentFormat.MARKDOWN);
        assertThat(restored.body().value()).isEqualTo("# body");
        assertThat(com.blogsystem.domain.content.model.Article.class.isAnnotationPresent(TableName.class)).isFalse();
    }
}
