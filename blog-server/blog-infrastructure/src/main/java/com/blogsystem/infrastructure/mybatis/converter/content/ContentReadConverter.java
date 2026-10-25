package com.blogsystem.infrastructure.mybatis.converter.content;

import com.blogsystem.domain.content.query.ArticleView;
import com.blogsystem.domain.content.query.CategoryView;
import com.blogsystem.domain.content.query.TagView;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;
import com.blogsystem.infrastructure.mybatis.record.content.CategoryRecord;
import com.blogsystem.infrastructure.mybatis.record.content.TagRecord;

public final class ContentReadConverter {
    private ContentReadConverter() {}

    public static ArticleView article(ArticleRecord row) {
        return new ArticleView(
                row.getId(),
                row.getAuthorId(),
                row.getTitle(),
                row.getSummary(),
                row.getContentMd(),
                row.getContentHtml(),
                row.getCoverUrl(),
                row.getCategoryId(),
                row.getStatus(),
                row.getIsTop(),
                row.getIsCommentEnabled(),
                row.getViewCount(),
                row.getLikeCount(),
                row.getCollectCount(),
                row.getPublishTime(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getDeleted(),
                row.getCategoryName(),
                row.getTagNames(),
                row.getTagIds());
    }

    public static CategoryView category(CategoryRecord row) {
        return new CategoryView(
                row.getId(),
                row.getName(),
                row.getSlug(),
                row.getDescription(),
                row.getSort(),
                row.getStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getDeleted());
    }

    public static TagView tag(TagRecord row) {
        return new TagView(
                row.getId(),
                row.getName(),
                row.getSlug(),
                row.getColor(),
                row.getSort(),
                row.getStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getDeleted(),
                row.getIsHot());
    }
}
