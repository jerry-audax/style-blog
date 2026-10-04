package com.blogsystem.content.domain.model;

public enum ArticleStatus {
    DRAFT(0),
    PUBLISHED(1);

    private final int legacyValue;

    ArticleStatus(int legacyValue) {
        this.legacyValue = legacyValue;
    }

    public int legacyValue() {
        return legacyValue;
    }

    public static ArticleStatus fromLegacyValue(Integer value) {
        if (value == null) {
            return DRAFT;
        }
        for (ArticleStatus status : values()) {
            if (status.legacyValue == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown article status: " + value);
    }
}
