package com.blogsystem.content.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.content.domain.model.*;
import com.blogsystem.content.domain.query.*;
import com.blogsystem.content.domain.repository.*;
import com.blogsystem.shared.domain.PageResult;
import com.blogsystem.shared.infrastructure.redis.RedisFailurePolicy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

import java.util.*;
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

    public PageResult<ArticleView> listArticles(Integer status, Long categoryId, Long tagId, long pageNum, long pageSize) {
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>().eq(Article::getDeleted, 0)
                .orderByDesc(Article::getIsTop).orderByDesc(Article::getViewCount)
                .orderByDesc(Article::getPublishTime).orderByDesc(Article::getId);
        if (status != null) {
            wrapper.eq(Article::getStatus, status);
        }
        if (categoryId != null) {
            wrapper.eq(Article::getCategoryId, categoryId);
        }
        if (tagId != null) {
            List<Long> articleIds = articleTagMapper.selectList(
                            new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getTagId, tagId))
                    .stream().map(ArticleTag::getArticleId).toList();
            if (articleIds.isEmpty()) {
                wrapper.eq(Article::getId, -1L);
            } else {
                wrapper.in(Article::getId, articleIds);
            }
        }
        Page<Article> page = articleMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<Article> records = page.getRecords();
        if (records.isEmpty()) {
            return toPage(page);
        }

        Set<Long> categoryIds = records.stream().map(Article::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNameMap = categoryIds.isEmpty() ? Map.of() :
                categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, c -> c.getName() != null ? c.getName() : ""));

        Set<Long> articleIds = records.stream().map(Article::getId).collect(Collectors.toSet());
        List<ArticleTag> allMappings = articleTagMapper.selectList(
                new LambdaQueryWrapper<ArticleTag>().in(ArticleTag::getArticleId, articleIds));
        Map<Long, List<Long>> articleTagIdMap = allMappings.stream()
                .collect(Collectors.groupingBy(ArticleTag::getArticleId,
                        Collectors.mapping(ArticleTag::getTagId, Collectors.toList())));

        Set<Long> allTagIds = allMappings.stream().map(ArticleTag::getTagId).collect(Collectors.toSet());
        Map<Long, String> tagNameMap = allTagIds.isEmpty() ? Map.of() :
                tagMapper.selectBatchIds(allTagIds).stream()
                        .collect(Collectors.toMap(Tag::getId, t -> t.getName() != null ? t.getName() : ""));

        for (Article a : records) {
            a.setCategoryName(a.getCategoryId() == null ? null : categoryNameMap.get(a.getCategoryId()));
            List<Long> tagIdsForArticle = articleTagIdMap.getOrDefault(a.getId(), List.of());
            a.setTagIds(tagIdsForArticle);
            a.setTagNames(tagIdsForArticle.stream().map(tagNameMap::get).filter(Objects::nonNull).collect(Collectors.toList()));
        }
        return toPage(page);
    }


    public List<ArticleView> listHotArticles(long limit) {
        // Try to get top N from the hot-by-likes ZSet (CACHE — degrade to DB on failure)
        try {
            Set<String> topIds = redisTemplate.opsForZSet().reverseRange(LIKE_ZSET, 0, limit - 1);
            if (topIds != null && !topIds.isEmpty()) {
                List<Long> ids = topIds.stream().map(Long::valueOf).collect(Collectors.toList());
                List<Article> articles = articleMapper.selectBatchIds(ids).stream()
                        .filter(a -> Integer.valueOf(1).equals(a.getStatus()) && Integer.valueOf(0).equals(a.getDeleted()))
                        .collect(Collectors.toCollection(ArrayList::new));
                articles.sort((a, b) -> {
                    int topCmp = Integer.compare(
                            b.getIsTop() == null ? 0 : b.getIsTop(),
                            a.getIsTop() == null ? 0 : a.getIsTop());
                    if (topCmp != 0) return topCmp;
                    return Integer.compare(
                            b.getLikeCount() == null ? 0 : b.getLikeCount(),
                            a.getLikeCount() == null ? 0 : a.getLikeCount());
                });
                if (articles.size() == ids.size()) return articles.stream().map(ContentReadMapper::article).toList();
            }
        } catch (Exception e) {
            redisFailurePolicy.onCacheFailure("reverseRange hotByLikes", LIKE_ZSET, e);
        }
        // Cache miss or Redis failure → fall back to DB query and rebuild ZSet
        List<Article> list = articleMapper.selectList(new LambdaQueryWrapper<Article>().eq(Article::getDeleted, 0)
                .eq(Article::getStatus, 1)
                .orderByDesc(Article::getIsTop)
                .orderByDesc(Article::getLikeCount)
                .orderByDesc(Article::getPublishTime)
                .orderByDesc(Article::getId)
                .last("limit " + limit));
        // Rebuild ZSet (best-effort, failure is non-critical)
        for (Article a : list) {
            try {
                redisTemplate.opsForZSet().add(LIKE_ZSET, String.valueOf(a.getId()),
                        a.getLikeCount() == null ? 0 : a.getLikeCount());
            } catch (Exception e) {
                redisFailurePolicy.onCacheFailure("rebuild hotByLikes ZSet add", LIKE_ZSET, e);
                break; // If one fails, the rest will likely fail too
            }
        }
        return list.stream().map(ContentReadMapper::article).toList();
    }

    @Override
    public Optional<ArticleView> findArticle(Long id, boolean includeDrafts) {
        LambdaQueryWrapper<Article> query = new LambdaQueryWrapper<Article>()
                .eq(Article::getId, id).eq(Article::getDeleted, 0);
        if (!includeDrafts) query.eq(Article::getStatus, 1);
        return Optional.ofNullable(articleMapper.selectOne(query)).map(row -> {
            List<Long> ids = articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                            .eq(ArticleTag::getArticleId, id).orderByAsc(ArticleTag::getId))
                    .stream().map(ArticleTag::getTagId).toList();
            row.setTagIds(ids);
            row.setTagNames(ids.isEmpty() ? List.of() : tagMapper.selectBatchIds(ids).stream()
                    .map(Tag::getName).filter(Objects::nonNull).toList());
            if (row.getCategoryId() != null) {
                Category category = categoryMapper.selectById(row.getCategoryId());
                row.setCategoryName(category == null ? null : category.getName());
            }
            return ContentReadMapper.article(row);
        });
    }

    @Override
    public int incrementViews(Long id, boolean includeDrafts) {
        LambdaUpdateWrapper<Article> update = new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id).eq(Article::getDeleted, 0);
        if (!includeDrafts) update.eq(Article::getStatus, 1);
        if (articleMapper.update(null, update.setSql("view_count = COALESCE(view_count, 0) + 1")) != 1)
            throw new IllegalArgumentException("文章不存在");
        Article article = articleMapper.selectById(id);
        if (article == null || !Integer.valueOf(0).equals(article.getDeleted())
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

    private PageResult<ArticleView> toPage(Page<Article> page) {
        return new PageResult<>(page.getRecords().stream().map(ContentReadMapper::article).toList(),
                page.getCurrent(), page.getSize(), page.getTotal());
    }
}
