package com.blogsystem.domain.music.model;

import com.blogsystem.domain.music.model.PersistedMusicTrack;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PersistedMusicTrackTest {

    @Test
    void preservesExistingTrackIdentityAndAssetReferences() {
        UUID audio = UUID.fromString("3d6c4773-1108-42db-a07e-e1a2b73a50fc");
        UUID cover = UUID.fromString("3f733d51-d7bf-4f47-ae9b-e2f729b49957");
        UUID lyrics = UUID.fromString("8b290d11-bf04-44e7-a5ad-0a2ff0903ad7");

        PersistedMusicTrack track = new PersistedMusicTrack(
                "existing_track-01", "宇多田の歌", "宇多田ヒカル", "13612345",
                audio, cover, lyrics, "https://example.test/cover.jpg", true, 7);

        assertEquals("existing_track-01", track.id());
        assertEquals(audio, track.audioAssetId());
        assertEquals(cover, track.coverAssetId());
        assertEquals(lyrics, track.lyricsAssetId());
    }

    @Test
    void acceptsTracksWithoutMediaReferencesOrExternalArtwork() {
        PersistedMusicTrack track = track("a", "歌", "", null, null, 0);

        assertNull(track.audioAssetId());
        assertNull(track.coverAssetId());
        assertNull(track.lyricsAssetId());
        assertNull(track.sourceId());
        assertNull(track.externalCoverUrl());
        assertEquals("", track.artist());
    }

    @Test
    void acceptsMaximumMetadataLengthsAndSortOrder() {
        PersistedMusicTrack track = track("i".repeat(64), "t".repeat(200), "a".repeat(200),
                "s".repeat(64), null, 100000);

        assertEquals("i".repeat(64), track.id());
        assertEquals("t".repeat(200), track.title());
        assertEquals("a".repeat(200), track.artist());
        assertEquals("s".repeat(64), track.sourceId());
        assertEquals(100000, track.sortOrder());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingTrackIdentity(String id) {
        assertThrows(IllegalArgumentException.class, () -> track(id, "Song", "", null, null, 0));
    }

    @Test
    void rejectsTrackIdentityLongerThan64Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> track("i".repeat(65), "Song", "", null, null, 0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingTitle(String title) {
        assertThrows(IllegalArgumentException.class, () -> track("track", title, "", null, null, 0));
    }

    @Test
    void rejectsTitleLongerThan200Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> track("track", "t".repeat(201), "", null, null, 0));
    }

    @Test
    void rejectsNullArtist() {
        assertThrows(IllegalArgumentException.class, () -> track("track", "Song", null, null, null, 0));
    }

    @Test
    void rejectsArtistLongerThan200Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> track("track", "Song", "a".repeat(201), null, null, 0));
    }

    @Test
    void acceptsAnEmptySourceIdentityWithoutInventingOne() {
        assertEquals("", track("track", "Song", "", "", null, 0).sourceId());
    }

    @Test
    void rejectsSourceIdentityLongerThan64Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> track("track", "Song", "", "s".repeat(65), null, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 100001})
    void rejectsSortOrderOutsideSupportedRange(int sortOrder) {
        assertThrows(IllegalArgumentException.class,
                () -> track("track", "Song", "", null, null, sortOrder));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.test/cover.jpg",
            "https://example.test/cover.jpg?width=300",
            "HTTPS://example.test/cover.jpg"
    })
    void acceptsAbsoluteHttpArtworkUrls(String url) {
        assertEquals(url, track("track", "Song", "", null, url, 0).externalCoverUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "cover.jpg", "//example.test/cover.jpg", "ftp://example.test/cover.jpg",
            "file:///cover.jpg", "data:image/png;base64,abc", "https:/cover.jpg", "https://",
            "https://example.test/a b.jpg", "https://user:password@example.test/cover.jpg",
            "http://user@example.test/cover.jpg"
    })
    void rejectsArtworkUrlsWithoutHttpOriginOrWithUserInformation(String url) {
        assertThrows(IllegalArgumentException.class, () -> track("track", "Song", "", null, url, 0));
    }

    private static PersistedMusicTrack track(String id, String title, String artist,
                                              String sourceId, String externalCoverUrl, int sortOrder) {
        return new PersistedMusicTrack(id, title, artist, sourceId, null, null, null,
                externalCoverUrl, true, sortOrder);
    }
}
