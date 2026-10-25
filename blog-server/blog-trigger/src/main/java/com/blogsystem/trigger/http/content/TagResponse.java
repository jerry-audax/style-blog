package com.blogsystem.trigger.http.content;

import com.blogsystem.domain.content.query.TagView;
import java.time.LocalDateTime;

/** Transport IDs are strings; domain and persistence still use BIGINT. */
public record TagResponse(String id, String name, String slug, String color, Integer sort,
        Integer status, LocalDateTime createdAt, LocalDateTime updatedAt, Integer deleted, Integer isHot) {
    public static TagResponse from(TagView tag) {
        return new TagResponse(tag.id().toString(), tag.name(), tag.slug(), tag.color(), tag.sort(),
                tag.status(), tag.createdAt(), tag.updatedAt(), tag.deleted(), tag.isHot());
    }
}
