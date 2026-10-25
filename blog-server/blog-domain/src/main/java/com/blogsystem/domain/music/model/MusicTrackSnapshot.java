package com.blogsystem.domain.music.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Immutable committed catalogue state including the editor's optimistic version. */
public record MusicTrackSnapshot(
        PersistedMusicTrack track, long version, Instant createdAt, Instant updatedAt) {
    public MusicTrackSnapshot {
        if (track == null
                || version < 0
                || createdAt == null
                || updatedAt == null
                || updatedAt.isBefore(createdAt))
            throw new IllegalArgumentException("Invalid music snapshot");
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
