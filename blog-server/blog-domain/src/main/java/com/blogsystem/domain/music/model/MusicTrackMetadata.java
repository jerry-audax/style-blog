package com.blogsystem.domain.music.model;

/** Validated new editor input; never rewrites a historical catalogue identity. */
public record MusicTrackMetadata(
        String title, String artist, String sourceId, boolean enabled, int sortOrder) {
    public MusicTrackMetadata {
        title = text(title, 200, false);
        artist = text(artist == null ? "" : artist, 200, true);
        sourceId = sourceId == null ? null : text(sourceId, 64, true);
        if (sourceId != null && sourceId.isEmpty()) sourceId = null;
        if (sortOrder < 0 || sortOrder > 100000)
            throw new IllegalArgumentException("Invalid music sort order");
    }

    private static String text(String value, int max, boolean emptyAllowed) {
        if (value == null || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid music metadata");
        String normalized = value.trim();
        if (normalized.length() > max || (!emptyAllowed && normalized.isEmpty()))
            throw new IllegalArgumentException("Invalid music metadata");
        return normalized;
    }
}
