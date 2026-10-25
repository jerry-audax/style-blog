package com.blogsystem.domain.surprise.model;

import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistedSurpriseVideoTest {
    private static final UUID VIDEO_ASSET_ID =
            UUID.fromString("6f39a21b-9c22-44b5-98d7-bf914684a44d");

    @Test
    void preservesExistingIdentityWithoutTrimmingOrNormalizing() {
        PersistedSurpriseVideo video = new PersistedSurpriseVideo(
                "  Existing-视频-ID  ", "保留的视频", VIDEO_ASSET_ID, true, 0);

        assertEquals("  Existing-视频-ID  ", video.id());
        assertEquals(VIDEO_ASSET_ID, video.videoAssetId());
        assertTrue(video.enabled());
        assertEquals(0, video.sortOrder());
    }

    @Test
    void acceptsMaximumIdentityAndTitleLengthsAndSortOrder() {
        PersistedSurpriseVideo video = new PersistedSurpriseVideo(
                "i".repeat(64), "题".repeat(120), VIDEO_ASSET_ID, false, 100000);

        assertEquals(64, video.id().length());
        assertEquals(120, video.title().length());
        assertFalse(video.enabled());
        assertEquals(100000, video.sortOrder());
    }

    @Test
    void acceptsMinimumIdentityAndTitleLengths() {
        PersistedSurpriseVideo video = new PersistedSurpriseVideo(
                "i", "题", VIDEO_ASSET_ID, true, 1);

        assertEquals("i", video.id());
        assertEquals("题", video.title());
    }

    @Test
    void rejectsMissingBlankOrOversizedIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo(null, "视频", VIDEO_ASSET_ID, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo("", "视频", VIDEO_ASSET_ID, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo(" \t ", "视频", VIDEO_ASSET_ID, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo("i".repeat(65), "视频", VIDEO_ASSET_ID, true, 0));
    }

    @Test
    void rejectsMissingBlankOrOversizedTitle() {
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo("video", null, VIDEO_ASSET_ID, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo("video", "", VIDEO_ASSET_ID, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo("video", " \t\n ", VIDEO_ASSET_ID, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo("video", "题".repeat(121), VIDEO_ASSET_ID, true, 0));
    }

    @Test
    void rejectsMissingVideoAssetIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo("video", "视频", null, true, 0));
    }

    @Test
    void rejectsSortOrderOutsideTheDatabaseRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo("video", "视频", VIDEO_ASSET_ID, true, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedSurpriseVideo("video", "视频", VIDEO_ASSET_ID, true, 100001));
    }
}
