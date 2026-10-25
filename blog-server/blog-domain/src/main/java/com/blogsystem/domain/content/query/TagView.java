package com.blogsystem.domain.content.query;

import java.time.LocalDateTime;

public record TagView(
        Long id,
        String name,
        String slug,
        String color,
        Integer sort,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer deleted,
        Integer isHot) {
    public TagView(Long id, String name, String slug, String color, Integer sort, Integer status,
            LocalDateTime createdAt, LocalDateTime updatedAt, Integer deleted) {
        this(id, name, slug, color, sort, status, createdAt, updatedAt, deleted, 0);
    }
}
