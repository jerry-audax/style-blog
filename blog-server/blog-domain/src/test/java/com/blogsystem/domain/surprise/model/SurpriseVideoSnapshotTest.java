package com.blogsystem.domain.surprise.model;

import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.domain.surprise.model.SurpriseVideoMetadata;
import com.blogsystem.domain.surprise.model.SurpriseVideoSnapshot;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SurpriseVideoSnapshotTest {
    @Test void preservesHistoricalIdAndReferences() {
        UUID asset = UUID.randomUUID();
        var video = new PersistedSurpriseVideo("  Existing-视频-ID  ", "历史视频", asset, false, 0);
        var snapshot = new SurpriseVideoSnapshot(video, 7, Instant.EPOCH, Instant.ofEpochSecond(1, 123456789));
        assertEquals("  Existing-视频-ID  ", snapshot.video().id()); assertEquals(asset, snapshot.video().videoAssetId());
        assertEquals(Instant.ofEpochSecond(1, 123456000), snapshot.updatedAt());
        assertEquals("视频", new SurpriseVideoMetadata(" 视频 ", true, 0).title());
    }
    @Test void rejectsNegativeVersionAndInvalidMetadata() {
        var video = new PersistedSurpriseVideo("id", "title", UUID.randomUUID(), true, 0);
        assertThrows(IllegalArgumentException.class, () -> new SurpriseVideoSnapshot(video, -1, Instant.EPOCH, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new SurpriseVideoSnapshot(video, 0, Instant.EPOCH, Instant.EPOCH.minusSeconds(1)));
        for (int sort : new int[]{-1, 100001}) assertThrows(IllegalArgumentException.class, () -> new SurpriseVideoMetadata("title", true, sort));
        for (String title : new String[]{" ", "x".repeat(121), "a\nb"}) assertThrows(IllegalArgumentException.class, () -> new SurpriseVideoMetadata(title, true, 0));
    }
}
