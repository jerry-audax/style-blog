package com.blogsystem.music.domain.model;

public record MusicTrack(String id, String title, String artist, String sourceId, String audioPath,
                         String coverPath, String lyricsPath, boolean enabled, int sortOrder) {
    public MusicTrack {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid music track ID");
        title = text(title, "未命名歌曲", 200);
        artist = text(artist, "", 200);
        sourceId = text(sourceId, "", 64);
        audioPath = path(audioPath);
        coverPath = path(coverPath);
        lyricsPath = path(lyricsPath);
        if (sortOrder < 0 || sortOrder > 100000) throw new IllegalArgumentException("Invalid music sort order");
    }

    public MusicTrack withMetadata(String title, String artist, String sourceId, boolean enabled, int sortOrder) {
        return new MusicTrack(id, title, artist, sourceId, audioPath, coverPath, lyricsPath, enabled, sortOrder);
    }

    public MusicTrack withMedia(MusicMediaKind kind, String path) {
        return switch (kind) {
            case AUDIO -> new MusicTrack(id, title, artist, sourceId, path, coverPath, lyricsPath, enabled, sortOrder);
            case COVER -> new MusicTrack(id, title, artist, sourceId, audioPath, path, lyricsPath, enabled, sortOrder);
            case LYRICS -> new MusicTrack(id, title, artist, sourceId, audioPath, coverPath, path, enabled, sortOrder);
        };
    }

    private static String text(String value, String fallback, int max) {
        String normalized = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (normalized.length() > max) throw new IllegalArgumentException("Music metadata too long");
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String path(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && (normalized.length() > 512 || normalized.contains("\\") || normalized.contains("?") || normalized.contains("#")
                || normalized.contains("..") || normalized.startsWith("/")))
            throw new IllegalArgumentException("Invalid music media path");
        return normalized;
    }
}
