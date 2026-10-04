package com.blogsystem.content.domain.query;

import java.time.LocalDateTime;

public record CategoryView(Long id, String name, String slug, String description, Integer sort,
                           Integer status, LocalDateTime createdAt, LocalDateTime updatedAt, Integer deleted) {
}

