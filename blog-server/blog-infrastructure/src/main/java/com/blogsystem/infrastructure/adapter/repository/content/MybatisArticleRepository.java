package com.blogsystem.infrastructure.adapter.repository.content;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;
import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.adapter.repository.ArticleRepository;
import com.blogsystem.infrastructure.mybatis.converter.content.ArticleConverter;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleTagMapper;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleTagRecord;
import com.blogsystem.infrastructure.redis.content.HotArticleCache;
import com.blogsystem.infrastructure.rendering.content.ArticleBodyRenderer;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Repository
public class MybatisArticleRepository implements ArticleRepository {
    private final ArticleMapper mapper;
    private final ArticleTagMapper tags;
    private final ArticleBodyRenderer renderer;
    private final HotArticleCache cache;
    private final MediaReferenceWriteRepository mediaReferences;

    public MybatisArticleRepository(
            ArticleMapper mapper,
            ArticleTagMapper tags,
            ArticleBodyRenderer renderer,
            HotArticleCache cache,
            MediaReferenceWriteRepository mediaReferences) {
        this.mapper = mapper;
        this.tags = tags;
        this.renderer = renderer;
        this.cache = cache;
        this.mediaReferences = Objects.requireNonNull(mediaReferences);
    }

    @Override
    public Optional<com.blogsystem.domain.content.model.Article> findById(ArticleId id) {
        return Optional.ofNullable(mapper.selectById(id.value()))
                .filter(row -> Integer.valueOf(0).equals(row.getDeleted()))
                .map(row -> ArticleConverter.toDomain(row, tagIds(id.value())));
    }

    @Override
    @Transactional
    public com.blogsystem.domain.content.model.Article save(
            com.blogsystem.domain.content.model.Article aggregate) {
        ArticleRecord row =
                ArticleConverter.fromDomain(aggregate, renderer.render(aggregate.body()));
        ArticleRecord previous = row.getId() == null ? null : mapper.selectById(row.getId());
        if (row.getId() != null && (previous == null || !Integer.valueOf(0).equals(previous.getDeleted()))) {
            throw referenceConflict();
        }
        mediaReferences.lockForWrite(references(previous), references(row));
        if (previous != null) {
            ArticleRecord locked = mapper.selectActiveForUpdate(row.getId());
            if (locked == null || !sameMedia(previous, locked)) throw referenceConflict();
        }
        if (row.getId() == null) {
            row.setDeleted(0);
            row.setViewCount(0);
            row.setLikeCount(0);
            row.setCollectCount(0);
            if (mapper.insert(row) != 1 || row.getId() == null)
                throw new IllegalStateException("文章保存失败");
        } else {
            // Only editable columns: never overwrite counters or authors from a stale read.
            int changed =
                    mapper.update(
                            null,
                            new LambdaUpdateWrapper<ArticleRecord>()
                                    .eq(ArticleRecord::getId, row.getId())
                                    .eq(ArticleRecord::getDeleted, 0)
                                    .set(ArticleRecord::getTitle, row.getTitle())
                                    .set(ArticleRecord::getSummary, row.getSummary())
                                    .set(ArticleRecord::getContentMd, row.getContentMd())
                                    .set(ArticleRecord::getContentHtml, row.getContentHtml())
                                    .set(ArticleRecord::getCoverUrl, row.getCoverUrl())
                                    .set(ArticleRecord::getCategoryId, row.getCategoryId())
                                    .set(ArticleRecord::getStatus, row.getStatus())
                                    .set(ArticleRecord::getIsTop, row.getIsTop())
                                    .set(
                                            ArticleRecord::getIsCommentEnabled,
                                            row.getIsCommentEnabled())
                                    .set(ArticleRecord::getPublishTime, row.getPublishTime())
                                    .set(ArticleRecord::getUpdatedAt, row.getUpdatedAt()));
            if (changed != 1) throw new IllegalArgumentException("文章不存在");
        }
        tags.delete(
                new LambdaQueryWrapper<ArticleTagRecord>()
                        .eq(ArticleTagRecord::getArticleId, row.getId()));
        for (Long id : aggregate.revision().tagIds()) {
            ArticleTagRecord tag = new ArticleTagRecord();
            tag.setArticleId(row.getId());
            tag.setTagId(id);
            if (tags.insert(tag) != 1) throw new IllegalStateException("文章标签保存失败");
        }
        cache.evictAfterCommit();
        return ArticleConverter.toDomain(row, aggregate.revision().tagIds());
    }

    @Override
    public void delete(ArticleId id) {
        if (mapper.delete(
                        new LambdaQueryWrapper<ArticleRecord>()
                                .eq(ArticleRecord::getId, id.value())
                                .eq(ArticleRecord::getDeleted, 0))
                != 1) throw new IllegalArgumentException("文章不存在");
        cache.evictAfterCommit();
    }

    private List<Long> tagIds(Long articleId) {
        return tags
                .selectList(
                        new LambdaQueryWrapper<ArticleTagRecord>()
                                .eq(ArticleTagRecord::getArticleId, articleId))
                .stream()
                .map(ArticleTagRecord::getTagId)
                .toList();
    }

    private static MediaReferenceWriteIntent references(ArticleRecord row) {
        if (row == null) return MediaReferenceWriteIntent.empty();
        return new MediaReferenceWriteIntent(
                Stream.of(row.getCoverUrl()).filter(Objects::nonNull).toList(),
                Stream.of(row.getContentMd()).filter(Objects::nonNull).toList(),
                Stream.of(row.getContentHtml()).filter(Objects::nonNull).toList(),
                Set.of());
    }

    private static boolean sameMedia(ArticleRecord previous, ArticleRecord current) {
        return Objects.equals(previous.getCoverUrl(), current.getCoverUrl())
                && Objects.equals(previous.getContentMd(), current.getContentMd())
                && Objects.equals(previous.getContentHtml(), current.getContentHtml());
    }

    private static MediaReferenceWriteException referenceConflict() {
        return new MediaReferenceWriteException(MediaReferenceWriteException.Reason.CONFLICT);
    }
}
