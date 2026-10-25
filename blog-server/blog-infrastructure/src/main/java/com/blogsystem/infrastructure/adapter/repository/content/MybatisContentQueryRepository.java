package com.blogsystem.infrastructure.adapter.repository.content;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.domain.content.query.ArticleView;
import com.blogsystem.domain.content.adapter.repository.ContentQueryRepository;
import com.blogsystem.infrastructure.mybatis.converter.content.ContentReadConverter;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleTagMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.CategoryMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.TagMapper;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleTagRecord;
import com.blogsystem.infrastructure.mybatis.record.content.CategoryRecord;
import com.blogsystem.infrastructure.mybatis.record.content.TagRecord;
import com.blogsystem.infrastructure.redis.support.RedisFailurePolicy;
import com.blogsystem.types.model.PageResult;

import lombok.RequiredArgsConstructor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class MybatisContentQueryRepository implements ContentQueryRepository {
    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailurePolicy redisFailurePolicy;
    private static final String HOT_ZSET = "cache:hotArticles";
    private static final String LIKE_ZSET = "cache:hotByLikes";

    public PageResult<ArticleView> listArticles(
            Integer status, Long categoryId, Long tagId, long pageNum, long pageSize) {
        LambdaQueryWrapper<ArticleRecord> wrapper =
                new LambdaQueryWrapper<ArticleRecord>()
                        .eq(ArticleRecord::getDeleted, 0)
                        .orderByDesc(ArticleRecord::getIsTop)
                        .orderByDesc(ArticleRecord::getViewCount)
                        .orderByDesc(ArticleRecord::getPublishTime)
                        .orderByDesc(ArticleRecord::getId);
        if (status != null) {
            wrapper.eq(ArticleRecord::getStatus, status);
        }
        if (categoryId != null) {
            wrapper.eq(ArticleRecord::getCategoryId, categoryId);
        }
        if (tagId != null) {
            List<Long> articleIds =
                    articleTagMapper
                            .selectList(
                                    new LambdaQueryWrapper<ArticleTagRecord>()
                                            .eq(ArticleTagRecord::getTagId, tagId))
                            .stream()
                            .map(ArticleTagRecord::getArticleId)
                            .toList();
            if (articleIds.isEmpty()) {
                wrapper.eq(ArticleRecord::getId, -1L);
            } else {
                wrapper.in(ArticleRecord::getId, articleIds);
            }
        }
        Page<ArticleRecord> page = articleMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<ArticleRecord> records = page.getRecords();
        if (records.isEmpty()) {
            return toPage(page);
        }

        Set<Long> categoryIds =
                records.stream()
                        .map(ArticleRecord::getCategoryId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Map<Long, String> categoryNameMap =
                categoryIds.isEmpty()
                        ? Map.of()
                        : categoryMapper.selectBatchIds(categoryIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                CategoryRecord::getId,
                                                c -> c.getName() != null ? c.getName() : ""));

        Set<Long> articleIds =
                records.stream().map(ArticleRecord::getId).collect(Collectors.toSet());
        List<ArticleTagRecord> allMappings =
                articleTagMapper.selectList(
                        new LambdaQueryWrapper<ArticleTagRecord>()
                                .in(ArticleTagRecord::getArticleId, articleIds));
        Map<Long, List<Long>> articleTagIdMap =
                allMappings.stream()
                        .collect(
                                Collectors.groupingBy(
                                        ArticleTagRecord::getArticleId,
                                        Collectors.mapping(
                                                ArticleTagRecord::getTagId, Collectors.toList())));

        Set<Long> allTagIds =
                allMappings.stream().map(ArticleTagRecord::getTagId).collect(Collectors.toSet());
        Map<Long, String> tagNameMap =
                allTagIds.isEmpty()
                        ? Map.of()
                        : tagMapper.selectBatchIds(allTagIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                TagRecord::getId,
                                                t -> t.getName() != null ? t.getName() : ""));

        for (ArticleRecord a : records) {
            a.setCategoryName(
                    a.getCategoryId() == null ? null : categoryNameMap.get(a.getCategoryId()));
            List<Long> tagIdsForArticle = articleTagIdMap.getOrDefault(a.getId(), List.of());
            a.setTagIds(tagIdsForArticle);
            a.setTagNames(
                    tagIdsForArticle.stream()
                            .map(tagNameMap::get)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList()));
        }
        return toPage(page);
    }

    public List<ArticleView> listHotArticles(long limit) {
        // Try to get top N from the hot-by-likes ZSet (CACHE — degrade to DB on failure)
        try {
            Set<String> topIds = redisTemplate.opsForZSet().reverseRange(LIKE_ZSET, 0, limit - 1);
            if (topIds != null && !topIds.isEmpty()) {
                List<Long> ids = topIds.stream().map(Long::valueOf).collect(Collectors.toList());
                List<ArticleRecord> articles =
                        articleMapper.selectBatchIds(ids).stream()
                                .filter(
                                        a ->
                                                Integer.valueOf(1).equals(a.getStatus())
                                                        && Integer.valueOf(0)
                                                                .equals(a.getDeleted()))
                                .collect(Collectors.toCollection(ArrayList::new));
                articles.sort(
                        (a, b) -> {
                            int topCmp =
                                    Integer.compare(
                                            b.getIsTop() == null ? 0 : b.getIsTop(),
                                            a.getIsTop() == null ? 0 : a.getIsTop());
                            if (topCmp != 0) return topCmp;
                            return Integer.compare(
                                    b.getLikeCount() == null ? 0 : b.getLikeCount(),
                                    a.getLikeCount() == null ? 0 : a.getLikeCount());
                        });
                if (articles.size() == ids.size())
                    return articles.stream().map(ContentReadConverter::article).toList();
            }
        } catch (Exception e) {
            redisFailurePolicy.onCacheFailure("reverseRange hotByLikes", LIKE_ZSET, e);
        }
        // Cache miss or Redis failure → fall back to DB query and rebuild ZSet
        List<ArticleRecord> list =
                articleMapper.selectList(
                        new LambdaQueryWrapper<ArticleRecord>()
                                .eq(ArticleRecord::getDeleted, 0)
                                .eq(ArticleRecord::getStatus, 1)
                                .orderByDesc(ArticleRecord::getIsTop)
                                .orderByDesc(ArticleRecord::getLikeCount)
                                .orderByDesc(ArticleRecord::getPublishTime)
                                .orderByDesc(ArticleRecord::getId)
                                .last("limit " + limit));
        // Rebuild ZSet (best-effort, failure is non-critical)
        for (ArticleRecord a : list) {
            try {
                redisTemplate
                        .opsForZSet()
                        .add(
                                LIKE_ZSET,
                                String.valueOf(a.getId()),
                                a.getLikeCount() == null ? 0 : a.getLikeCount());
            } catch (Exception e) {
                redisFailurePolicy.onCacheFailure("rebuild hotByLikes ZSet add", LIKE_ZSET, e);
                break; // If one fails, the rest will likely fail too
            }
        }
        return list.stream().map(ContentReadConverter::article).toList();
    }

    @Override
    public Optional<ArticleView> findArticle(Long id, boolean includeDrafts) {
        LambdaQueryWrapper<ArticleRecord> query =
                new LambdaQueryWrapper<ArticleRecord>()
                        .eq(ArticleRecord::getId, id)
                        .eq(ArticleRecord::getDeleted, 0);
        if (!includeDrafts) query.eq(ArticleRecord::getStatus, 1);
        return Optional.ofNullable(articleMapper.selectOne(query))
                .map(
                        row -> {
                            List<Long> ids =
                                    articleTagMapper
                                            .selectList(
                                                    new LambdaQueryWrapper<ArticleTagRecord>()
                                                            .eq(ArticleTagRecord::getArticleId, id)
                                                            .orderByAsc(ArticleTagRecord::getId))
                                            .stream()
                                            .map(ArticleTagRecord::getTagId)
                                            .toList();
                            row.setTagIds(ids);
                            row.setTagNames(
                                    ids.isEmpty()
                                            ? List.of()
                                            : tagMapper.selectBatchIds(ids).stream()
                                                    .map(TagRecord::getName)
                                                    .filter(Objects::nonNull)
                                                    .toList());
                            if (row.getCategoryId() != null) {
                                CategoryRecord category =
                                        categoryMapper.selectById(row.getCategoryId());
                                row.setCategoryName(category == null ? null : category.getName());
                            }
                            return ContentReadConverter.article(row);
                        });
    }

    @Override
    public int incrementViews(Long id, boolean includeDrafts) {
        LambdaUpdateWrapper<ArticleRecord> update =
                new LambdaUpdateWrapper<ArticleRecord>()
                        .eq(ArticleRecord::getId, id)
                        .eq(ArticleRecord::getDeleted, 0);
        if (!includeDrafts) update.eq(ArticleRecord::getStatus, 1);
        if (articleMapper.update(null, update.setSql("view_count = COALESCE(view_count, 0) + 1"))
                != 1) throw new IllegalArgumentException("文章不存在");
        ArticleRecord article = articleMapper.selectById(id);
        if (article == null
                || !Integer.valueOf(0).equals(article.getDeleted())
                || (!includeDrafts && !Integer.valueOf(1).equals(article.getStatus())))
            throw new IllegalArgumentException("文章不存在");
        int count = article.getViewCount() == null ? 0 : article.getViewCount();
        try {
            redisTemplate.opsForZSet().add(HOT_ZSET, String.valueOf(id), count);
        } catch (Exception e) {
            redisFailurePolicy.onCacheFailure("update hotArticles ZSet viewCount", HOT_ZSET, e);
        }
        return count;
    }

    private PageResult<ArticleView> toPage(Page<ArticleRecord> page) {
        return new PageResult<>(
                page.getRecords().stream().map(ContentReadConverter::article).toList(),
                page.getCurrent(),
                page.getSize(),
                page.getTotal());
    }
}
