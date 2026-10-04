package com.blogsystem.music.domain.model;

import java.text.Normalizer;
import java.util.*;

/**
 * Match complete titles only. Ambiguous titles are deliberately not associated.
 */
public final class PlaylistSongMatcher {
    private PlaylistSongMatcher() {
    }

    public static Map<String, MusicPlaylist.Song> uniqueTitles(List<MusicPlaylist.Song> songs) {
        Map<String, MusicPlaylist.Song> result = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (var song : songs) {
            if (song == null || song.id() == null || !song.id().matches("[1-9]\\d{0,19}")) continue;
            String key = title(song.name());
            if (key.isEmpty() || ambiguous.contains(key)) continue;
            var previous = result.putIfAbsent(key, song);
            if (previous != null && !previous.id().equals(song.id())) {
                result.remove(key);
                ambiguous.add(key);
            }
        }
        return Map.copyOf(result);
    }

    public static String title(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
