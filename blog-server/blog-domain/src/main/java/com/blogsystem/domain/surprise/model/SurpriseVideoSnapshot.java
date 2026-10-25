package com.blogsystem.domain.surprise.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record SurpriseVideoSnapshot(
        PersistedSurpriseVideo video, long version, Instant createdAt, Instant updatedAt) {
    public SurpriseVideoSnapshot {
        if (video == null
                || version < 0
                || createdAt == null
                || updatedAt == null
                || updatedAt.isBefore(createdAt))
            throw new IllegalArgumentException("Invalid video snapshot");
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
