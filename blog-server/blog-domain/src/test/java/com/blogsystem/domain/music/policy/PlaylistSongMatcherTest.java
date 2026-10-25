package com.blogsystem.domain.music.policy;

import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.domain.music.policy.PlaylistSongMatcher;

import com.blogsystem.domain.music.model.MusicPlaylist.Song;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistSongMatcherTest {
    private MusicPlaylist.Song song(String id, String title) {
        return new MusicPlaylist.Song(id, title, "artist", "", false, false);
    }

    @Test
    void matchesFullTitlesAndRetainsVersions() {
        var unique = PlaylistSongMatcher.uniqueTitles(List.of(song("1", "  Ｓｏｎｇ "), song("2", "Song (Live)")));
        assertEquals("1", unique.get("song").id());
        assertEquals("2", unique.get("song (live)").id());
        assertNull(unique.get("song live"));
    }

    @Test
    void ambiguousNamesNeverUseTheFirstMatchButDuplicateSameIdIsSafe() {
        var unique = PlaylistSongMatcher.uniqueTitles(List.of(song("1", "Song"), song("2", " SONG "), song("3", "Song")));
        assertTrue(unique.isEmpty());
        assertEquals(1, PlaylistSongMatcher.uniqueTitles(List.of(song("1", "Song"), song("1", "Song"))).size());
    }
}
