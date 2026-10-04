package com.blogsystem.content.infrastructure.persistence;

import com.blogsystem.content.domain.query.*;

public final class ContentReadMapper {
    private ContentReadMapper() {
    }

    public static ArticleView article(Article row) {
        return new ArticleView(row.getId(), row.getAuthorId(), row.getTitle(), row.getSummary(),
                row.getContentMd(), row.getContentHtml(), row.getCoverUrl(), row.getCategoryId(), row.getStatus(),
                row.getIsTop(), row.getIsCommentEnabled(), row.getViewCount(), row.getLikeCount(),
                row.getCollectCount(), row.getPublishTime(), row.getCreatedAt(), row.getUpdatedAt(),
                row.getDeleted(), row.getCategoryName(), row.getTagNames(), row.getTagIds());
    }

    public static CategoryView category(Category row) {
        return new CategoryView(row.getId(), row.getName(), row.getSlug(), row.getDescription(),
                row.getSort(), row.getStatus(), row.getCreatedAt(), row.getUpdatedAt(), row.getDeleted());
    }

    public static TagView tag(Tag row) {
        return new TagView(row.getId(), row.getName(), row.getSlug(), row.getColor(),
                row.getSort(), row.getStatus(), row.getCreatedAt(), row.getUpdatedAt(), row.getDeleted());
    }
}
