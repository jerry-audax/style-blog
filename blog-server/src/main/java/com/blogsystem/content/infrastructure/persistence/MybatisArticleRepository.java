package com.blogsystem.content.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blogsystem.content.domain.model.ArticleId;
import com.blogsystem.content.domain.repository.ArticleRepository;
import com.blogsystem.content.infrastructure.cache.HotArticleCache;
import com.blogsystem.content.infrastructure.rendering.ArticleBodyRenderer;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisArticleRepository implements ArticleRepository {
    private final ArticleMapper mapper;
    private final ArticleTagMapper tags;
    private final ArticleBodyRenderer renderer;
    private final HotArticleCache cache;

    public MybatisArticleRepository(ArticleMapper mapper, ArticleTagMapper tags, ArticleBodyRenderer renderer, HotArticleCache cache) {
        this.mapper = mapper;
        this.tags = tags;
        this.renderer = renderer;
        this.cache = cache;
    }

    @Override
    public Optional<com.blogsystem.content.domain.model.Article> findById(ArticleId id) {
        return Optional.ofNullable(mapper.selectById(id.value()))
                .filter(row -> Integer.valueOf(0).equals(row.getDeleted()))
                .map(row -> ArticlePersistenceMapper.toDomain(row, tagIds(id.value())));
    }

    @Override
    public com.blogsystem.content.domain.model.Article save(com.blogsystem.content.domain.model.Article aggregate) {
        Article row = ArticlePersistenceMapper.fromDomain(aggregate, renderer.render(aggregate.body()));
        if (row.getId() == null) {
            row.setDeleted(0);
            row.setViewCount(0);
            row.setLikeCount(0);
            row.setCollectCount(0);
            if (mapper.insert(row) != 1 || row.getId() == null) throw new IllegalStateException("文章保存失败");
        } else {
            // Only editable columns: never overwrite counters or authors from a stale read.
            int changed = mapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, row.getId()).eq(Article::getDeleted, 0)
                    .set(Article::getTitle, row.getTitle()).set(Article::getSummary, row.getSummary())
                    .set(Article::getContentMd, row.getContentMd()).set(Article::getContentHtml, row.getContentHtml())
                    .set(Article::getCoverUrl, row.getCoverUrl()).set(Article::getCategoryId, row.getCategoryId())
                    .set(Article::getStatus, row.getStatus()).set(Article::getIsTop, row.getIsTop())
                    .set(Article::getIsCommentEnabled, row.getIsCommentEnabled())
                    .set(Article::getPublishTime, row.getPublishTime()).set(Article::getUpdatedAt, row.getUpdatedAt()));
            if (changed != 1) throw new IllegalArgumentException("文章不存在");
        }
        tags.delete(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId, row.getId()));
        for (Long id : aggregate.revision().tagIds()) {
            ArticleTag tag = new ArticleTag();
            tag.setArticleId(row.getId());
            tag.setTagId(id);
            if (tags.insert(tag) != 1) throw new IllegalStateException("文章标签保存失败");
        }
        cache.evictAfterCommit();
        return ArticlePersistenceMapper.toDomain(row, aggregate.revision().tagIds());
    }

    @Override
    public void delete(ArticleId id) {
        if (mapper.delete(new LambdaQueryWrapper<Article>().eq(Article::getId, id.value()).eq(Article::getDeleted, 0)) != 1)
            throw new IllegalArgumentException("文章不存在");
        cache.evictAfterCommit();
    }

    private List<Long> tagIds(Long articleId) {
        return tags.selectList(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId, articleId))
                .stream().map(ArticleTag::getTagId).toList();
    }
}
