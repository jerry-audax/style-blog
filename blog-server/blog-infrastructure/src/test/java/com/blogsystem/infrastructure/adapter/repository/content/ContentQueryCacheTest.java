package com.blogsystem.infrastructure.adapter.repository.content;

import com.blogsystem.infrastructure.adapter.repository.content.MybatisContentQueryRepository;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleTagMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.CategoryMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.TagMapper;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;

import org.assertj.core.api.Assertions;
import org.mockito.Mockito;

import com.blogsystem.infrastructure.redis.support.RedisFailurePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContentQueryCacheTest {
    @SuppressWarnings("unchecked")
    @Test
    void staleHotCacheCannotReturnDraftOrDeletedArticles() {
        var articles = mock(ArticleMapper.class);
        var redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.reverseRange("cache:hotByLikes", 0, 5)).thenReturn(new LinkedHashSet<>(List.of("1", "2", "3")));
        when(articles.selectBatchIds(anyCollection())).thenReturn(List.of(row(1L, 0, 0), row(2L, 1, 1), row(3L, 1, 0)));
        when(articles.selectList(any())).thenReturn(List.of(row(3L, 1, 0)));
        var repository = new MybatisContentQueryRepository(articles, mock(ArticleTagMapper.class), mock(CategoryMapper.class),
                mock(TagMapper.class), redis, mock(RedisFailurePolicy.class));
        assertThat(repository.listHotArticles(6)).extracting(view -> view.id()).containsExactly(3L);
        verify(articles).selectList(any());
    }

    @Test
    void redisOutageFallsBackToDatabaseInsteadOfFailingRead() {
        var articles = mock(ArticleMapper.class);
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForZSet()).thenThrow(new IllegalStateException("fake outage"));
        when(articles.selectList(any())).thenReturn(List.of(row(3L, 1, 0)));
        var failures = mock(RedisFailurePolicy.class);
        var repository = new MybatisContentQueryRepository(articles, mock(ArticleTagMapper.class), mock(CategoryMapper.class),
                mock(TagMapper.class), redis, failures);
        assertThat(repository.listHotArticles(6)).hasSize(1);
        verify(failures).onCacheFailure(eq("reverseRange hotByLikes"), eq("cache:hotByLikes"), any());
    }

    ArticleRecord row(Long id, int status, int deleted) {
        var row = new ArticleRecord();
        row.setId(id);
        row.setStatus(status);
        row.setDeleted(deleted);
        row.setIsTop(0);
        row.setLikeCount(0);
        return row;
    }
}
