package com.blogsystem.domain.surprise.model;

public record SurpriseVideoMetadata(String title, boolean enabled, int sortOrder) {
    public SurpriseVideoMetadata {
        if (title == null || title.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid video title");
        title = title.trim();
        if (title.isEmpty() || title.length() > 120)
            throw new IllegalArgumentException("Invalid video title");
        if (sortOrder < 0 || sortOrder > 100000)
            throw new IllegalArgumentException("Invalid video sort order");
    }
}
