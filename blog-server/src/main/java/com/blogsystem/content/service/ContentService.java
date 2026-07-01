package com.blogsystem.content.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.content.dto.ArticleSaveRequest;
import com.blogsystem.content.dto.CategorySaveRequest;
import com.blogsystem.content.dto.TagSaveRequest;
import com.blogsystem.content.entity.Article;
import com.blogsystem.content.entity.ArticleLike;
import com.blogsystem.content.entity.ArticleTag;
import com.blogsystem.content.entity.Category;
import com.blogsystem.content.entity.Tag;
import com.blogsystem.content.mapper.ArticleLikeMapper;
import com.blogsystem.content.mapper.ArticleMapper;
import com.blogsystem.content.mapper.ArticleTagMapper;
import com.blogsystem.content.mapper.CategoryMapper;
import com.blogsystem.content.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blogsystem.common.RedisFailurePolicy;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentService {

    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;
    private final ArticleLikeMapper articleLikeMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailurePolicy redisFailurePolicy;

    private static final String HOT_ZSET = "cache:hotArticles";
    private static final String LIKE_ZSET = "cache:hotByLikes";

    /**
     * Clear hot article caches. CACHE scenario — degrade gracefully on Redis failure.
     */
    private void clearHotCache() {
        try {
            redisTemplate.delete(HOT_ZSET);
        } catch (Exception e) {
            redisFailurePolicy.onCacheFailure("delete hotArticles ZSet", HOT_ZSET, e);
        }
        try {
            redisTemplate.delete(LIKE_ZSET);
        } catch (Exception e) {
            redisFailurePolicy.onCacheFailure("delete hotByLikes ZSet", LIKE_ZSET, e);
        }
    }

    @Transactional
    public Long saveArticle(ArticleSaveRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        Article article;
        if (request.id() == null) {
            article = new Article();
            article.setAuthorId(userId);
            article.setViewCount(0);
            article.setLikeCount(0);
            article.setCollectCount(0);
            article.setDeleted(0);
        } else {
            article = articleMapper.selectById(request.id());
            if (article == null || article.getDeleted() == 1) {
                throw new IllegalArgumentException("文章不存在");
            }
        }
        article.setTitle(request.title());
        article.setSummary(request.summary());
        article.setContentMd(request.contentMd());
        article.setContentHtml(mdToHtml(request.contentMd()));
        article.setCoverUrl(request.coverUrl());
        article.setCategoryId(request.categoryId());
        article.setStatus(request.status());
        article.setIsTop(request.isTop() == null ? 0 : request.isTop());
        article.setIsCommentEnabled(request.isCommentEnabled() == null ? 1 : request.isCommentEnabled());
        if (request.status() == 1 && article.getPublishTime() == null) {
            article.setPublishTime(LocalDateTime.now());
        }

        if (request.id() == null) {
            articleMapper.insert(article);
        } else {
            articleMapper.updateById(article);
        }

        articleTagMapper.delete(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId, article.getId()));
        if (request.tagIds() != null && !request.tagIds().isEmpty()) {
            for (Long tagId : request.tagIds()) {
                ArticleTag articleTag = new ArticleTag();
                articleTag.setArticleId(article.getId());
                articleTag.setTagId(tagId);
                articleTagMapper.insert(articleTag);
            }
        }
        clearHotCache();
        return article.getId();
    }

    public Page<Article> listArticles(Integer status, Long categoryId, Long tagId, long pageNum, long pageSize) {
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
            return page;
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
            a.setCategoryName(categoryNameMap.get(a.getCategoryId()));
            List<Long> tagIdsForArticle = articleTagIdMap.getOrDefault(a.getId(), List.of());
            a.setTagNames(tagIdsForArticle.stream().map(tagNameMap::get).filter(Objects::nonNull).collect(Collectors.toList()));
        }
        return page;
    }


    public List<Article> listHotArticles(long limit) {
        // Try to get top N from the hot-by-likes ZSet (CACHE — degrade to DB on failure)
        try {
            Set<String> topIds = redisTemplate.opsForZSet().reverseRange(LIKE_ZSET, 0, limit - 1);
            if (topIds != null && !topIds.isEmpty()) {
                List<Long> ids = topIds.stream().map(Long::valueOf).collect(Collectors.toList());
                List<Article> articles = articleMapper.selectBatchIds(ids);
                articles.sort((a, b) -> {
                    int topCmp = Integer.compare(
                            b.getIsTop() == null ? 0 : b.getIsTop(),
                            a.getIsTop() == null ? 0 : a.getIsTop());
                    if (topCmp != 0) return topCmp;
                    return Integer.compare(
                            b.getLikeCount() == null ? 0 : b.getLikeCount(),
                            a.getLikeCount() == null ? 0 : a.getLikeCount());
                });
                return articles;
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
        return list;
    }

    public Article getArticle(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null || article.getDeleted() == 1) {
            throw new IllegalArgumentException("文章不存在");
        }
        // Atomic: SET view_count = view_count + 1, avoids read-modify-write race
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .setSql("view_count = view_count + 1"));
        int newCount = (article.getViewCount() == null ? 0 : article.getViewCount()) + 1;
        article.setViewCount(newCount);
        // Update hotArticles ZSet (CACHE — best-effort)
        try {
            redisTemplate.opsForZSet().add(HOT_ZSET, String.valueOf(id), newCount);
        } catch (Exception e) {
            redisFailurePolicy.onCacheFailure("update hotArticles ZSet viewCount", HOT_ZSET, e);
        }
        return article;
    }

    public void deleteArticle(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new IllegalArgumentException("文章不存在");
        }
        articleMapper.deleteById(id);
        clearHotCache();
    }

    public Long saveCategory(CategorySaveRequest request) {
        Category category = request.id() == null ? new Category() : categoryMapper.selectById(request.id());
        if (request.id() != null && category == null) {
            throw new IllegalArgumentException("分类不存在");
        }
        category.setName(request.name());
        category.setSlug(request.slug());
        category.setDescription(request.description());
        category.setSort(request.sort() == null ? 0 : request.sort());
        category.setStatus(request.status() == null ? 1 : request.status());
        category.setDeleted(0);
        if (request.id() == null) {
            categoryMapper.insert(category);
        } else {
            categoryMapper.updateById(category);
        }
        return category.getId();
    }

    public List<Category> listCategory() {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>().eq(Category::getDeleted, 0)
                .orderByAsc(Category::getSort).orderByDesc(Category::getId));
    }

    public void deleteCategory(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在");
        }
        categoryMapper.deleteById(id);
    }

    @Transactional
    public boolean toggleLike(Long articleId) {
        Long userId = StpUtil.getLoginIdAsLong();
        Article article = articleMapper.selectById(articleId);
        if (article == null || article.getDeleted() == 1) {
            throw new IllegalArgumentException("文章不存在");
        }
        ArticleLike exist = articleLikeMapper.selectOne(new LambdaQueryWrapper<ArticleLike>()
                .eq(ArticleLike::getArticleId, articleId)
                .eq(ArticleLike::getUserId, userId)
                .last("limit 1"));
        if (exist != null) {
            // Unlike: delete record + atomic decrement (floor at 0)
            articleLikeMapper.deleteById(exist.getId());
            articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, articleId)
                    .setSql("like_count = GREATEST(like_count - 1, 0)"));
            int newLikeCount = Math.max(0, (article.getLikeCount() == null ? 0 : article.getLikeCount()) - 1);
            // Update ZSet (CACHE — best-effort)
            try {
                redisTemplate.opsForZSet().add(LIKE_ZSET, String.valueOf(articleId), newLikeCount);
            } catch (Exception e) {
                redisFailurePolicy.onCacheFailure("toggleLike unlike ZSet update", LIKE_ZSET, e);
            }
            return false;
        }
        // Like: insert record + atomic increment
        ArticleLike like = new ArticleLike();
        like.setArticleId(articleId);
        like.setUserId(userId);
        articleLikeMapper.insert(like);
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .setSql("like_count = like_count + 1"));
        int newLikeCount = (article.getLikeCount() == null ? 0 : article.getLikeCount()) + 1;
        // Update ZSet (CACHE — best-effort)
        try {
            redisTemplate.opsForZSet().add(LIKE_ZSET, String.valueOf(articleId), newLikeCount);
        } catch (Exception e) {
            redisFailurePolicy.onCacheFailure("toggleLike like ZSet update", LIKE_ZSET, e);
        }
        return true;
    }

    public Long saveTag(TagSaveRequest request) {
        Tag tag = request.id() == null ? new Tag() : tagMapper.selectById(request.id());
        if (request.id() != null && tag == null) {
            throw new IllegalArgumentException("标签不存在");
        }
        tag.setName(request.name());
        tag.setSlug(request.slug());
        tag.setColor(request.color());
        tag.setSort(request.sort() == null ? 0 : request.sort());
        tag.setStatus(request.status() == null ? 1 : request.status());
        tag.setDeleted(0);
        if (request.id() == null) {
            tagMapper.insert(tag);
        } else {
            tagMapper.updateById(tag);
        }
        return tag.getId();
    }

    public List<Tag> listTag() {
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>().eq(Tag::getDeleted, 0)
                .orderByAsc(Tag::getSort).orderByDesc(Tag::getId));
    }

    public void deleteTag(Long id) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new IllegalArgumentException("标签不存在");
        }
        tagMapper.deleteById(id);
    }

    private static final Parser MD_PARSER;
    private static final HtmlRenderer MD_RENDERER;
    static {
        MutableDataSet options = new MutableDataSet();
        MD_PARSER = Parser.builder(options).build();
        MD_RENDERER = HtmlRenderer.builder(options).build();
    }

    private String mdToHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        if (markdown.stripLeading().startsWith("<")) return markdown;
        return MD_RENDERER.render(MD_PARSER.parse(markdown));
    }
}
