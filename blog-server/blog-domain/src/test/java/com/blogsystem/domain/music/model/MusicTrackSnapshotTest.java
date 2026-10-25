package com.blogsystem.domain.music.model;

import com.blogsystem.domain.music.model.MusicTrackMetadata;
import com.blogsystem.domain.music.model.MusicTrackSnapshot;
import com.blogsystem.domain.music.model.PersistedMusicTrack;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MusicTrackSnapshotTest {
    @Test void preservesHistoricalIdAndReferences() {
        UUID audio = UUID.randomUUID();
        var track = new PersistedMusicTrack("  Existing-视频-ID  ", "历史歌", "", null, audio, null, null, null, false, 5);
        var snapshot = new MusicTrackSnapshot(track, 7, Instant.EPOCH, Instant.ofEpochSecond(1, 123456789));
        assertEquals("  Existing-视频-ID  ", snapshot.track().id());
        assertEquals(audio, snapshot.track().audioAssetId());
        assertNull(snapshot.track().sourceId());
        assertEquals(Instant.ofEpochSecond(1, 123456000), snapshot.updatedAt());
    }
    @Test void normalizesNewMetadataWithoutInventingSource() {
        var value = new MusicTrackMetadata("  曲名  ", null, "  ", true, 0);
        assertEquals("曲名", value.title()); assertEquals("", value.artist()); assertNull(value.sourceId());
    }
    @Test void rejectsNegativeVersionAndInvalidMetadata() {
        var track = new PersistedMusicTrack("id", "title", "", null, null, null, null, null, true, 0);
        assertThrows(IllegalArgumentException.class, () -> new MusicTrackSnapshot(track, -1, Instant.EPOCH, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new MusicTrackSnapshot(track, 0, Instant.EPOCH, Instant.EPOCH.minusSeconds(1)));
        for (int sort : new int[]{-1, 100001}) assertThrows(IllegalArgumentException.class, () -> new MusicTrackMetadata("title", "", null, true, sort));
        for (String title : new String[]{" ", "x".repeat(201), "a\nb"}) assertThrows(IllegalArgumentException.class, () -> new MusicTrackMetadata(title, "", null, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new MusicTrackMetadata("title", "x".repeat(201), null, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new MusicTrackMetadata("title", "", "x".repeat(65), true, 0));
    }
}
