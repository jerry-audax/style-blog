package com.blogsystem.content.domain.query;

import java.time.LocalDateTime;

public record TagView(Long id, String name, String slug, String color, Integer sort,
                      Integer status, LocalDateTime createdAt, LocalDateTime updatedAt, Integer deleted) {
}

