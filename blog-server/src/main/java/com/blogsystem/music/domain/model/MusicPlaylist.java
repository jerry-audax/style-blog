package com.blogsystem.music.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Contains only selected public playlist metadata, never provider tokens or encrypted resource IDs.
 */
public record MusicPlaylist(String playlistId, String name, Instant syncedAt, List<Song> songs) {
    public MusicPlaylist {
        if (playlistId == null || !playlistId.matches("[1-9]\\d{0,19}") || syncedAt == null || songs == null || songs.size() > 2000)
            throw new IllegalArgumentException("Invalid music playlist");
        songs = List.copyOf(songs);
    }

    public record Song(String id, String name, String artist, String cover, boolean ownerPlayable, boolean preview) {
    }
}
