package com.blogsystem.music.domain.model;

/**
 * The public playlist is selected by backend configuration, never by visitors.
 */
public record MusicPolicy(String playlistId) {
    public MusicPolicy {
        if (playlistId == null || !playlistId.matches("[1-9]\\d{0,19}"))
            throw new IllegalArgumentException("Invalid configured playlist ID");
    }
}
