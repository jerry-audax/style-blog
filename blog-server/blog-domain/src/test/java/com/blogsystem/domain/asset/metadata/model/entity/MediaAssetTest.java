package com.blogsystem.domain.asset.metadata.model.entity;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetPage;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetQuery;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.util.ArrayList;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MediaAssetTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-10T12:00:00.123456789Z");

    @Test
    void metadataKeepsUnknownValuesDistinctFromKnownZero() {
        MediaMetadata unknown = new MediaMetadata("song.mp3", null, null, null, null, null, null);
        MediaMetadata knownZero = new MediaMetadata("empty.mp3", null, 0L, null, null, null, 0L);

        assertNull(unknown.fileSize());
        assertNull(unknown.durationMs());
        assertEquals(0L, knownZero.fileSize());
        assertEquals(0L, knownZero.durationMs());
    }

    @Test
    void metadataRejectsInvalidKnownValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaMetadata("song.mp3", null, -1L, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaMetadata("song.mp3", null, null, null, 0, 100, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaMetadata("song.mp3", null, null, "A".repeat(64), null, null, null));
    }

    @Test
    void registersActiveAssetAtVersionZeroAndTruncatesToPostgresPrecision() {
        MediaAsset asset = MediaAsset.register(
                UUID.fromString("5c76ec5f-901f-4b79-98f2-6f4e0e9ad35b"),
                "cloudflare_imgbed",
                new MediaObjectKey("blog/music/宇多田/Blue%20LIVE.mp3"),
                MediaKind.AUDIO,
                new MediaMetadata("Blue LIVE.mp3", "audio/mpeg", null, null, null, null, null),
                CREATED_AT);

        assertEquals(MediaAssetStatus.ACTIVE, asset.status());
        assertEquals(0, asset.version());
        assertEquals(CREATED_AT.truncatedTo(ChronoUnit.MICROS), asset.createdAt());
        assertEquals(asset.createdAt(), asset.updatedAt());
    }

    @Test
    void revisingMetadataPreservesAssetIdentityAndCurrentVersion() {
        MediaAsset asset = MediaAsset.register(UUID.randomUUID(), "cloudflare_imgbed",
                new MediaObjectKey("blog/music/song.mp3"), MediaKind.AUDIO,
                new MediaMetadata("song.mp3", null, null, null, null, null, null), CREATED_AT);

        MediaAsset revised = asset.reviseMetadata(
                new MediaMetadata("Song.mp3", "audio/mpeg", 0L, null, null, null, 0L),
                CREATED_AT.plusSeconds(1));

        assertEquals(asset.id(), revised.id());
        assertEquals(asset.objectKey(), revised.objectKey());
        assertEquals(asset.kind(), revised.kind());
        assertEquals(asset.status(), revised.status());
        assertEquals(asset.version(), revised.version());
        assertEquals(asset.createdAt(), revised.createdAt());
        assertEquals("Song.mp3", revised.metadata().fileName());
    }

    @Test
    void rejectsMetadataRevisionBeforeCurrentUpdatedAt() {
        MediaAsset asset = MediaAsset.register(UUID.randomUUID(), "cloudflare_imgbed",
                new MediaObjectKey("blog/music/song.mp3"), MediaKind.AUDIO,
                new MediaMetadata("song.mp3", null, null, null, null, null, null), CREATED_AT);

        assertThrows(IllegalArgumentException.class,
                () -> asset.reviseMetadata(asset.metadata(), CREATED_AT.minusSeconds(1)));
    }

    @Test
    void queryAndPageRejectInvalidPaginationAndCopyRecords() {
        assertThrows(IllegalArgumentException.class, () -> new MediaAssetQuery(null, null, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MediaAssetQuery(null, null, 0, 101));

        List<MediaAsset> mutable = new java.util.ArrayList<>();
        MediaAssetPage page = new MediaAssetPage(mutable, 0, 0, 1);
        mutable.add(MediaAsset.register(UUID.randomUUID(), "cloudflare_imgbed",
                new MediaObjectKey("blog/images/a.png"), MediaKind.IMAGE,
                new MediaMetadata("a.png", "image/png", null, null, null, null, null), CREATED_AT));

        assertTrue(page.records().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> page.records().add(null));
    }
}
