package com.blogsystem.infrastructure.mybatis.record.music;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Database row; conversion remains on the infrastructure side. */
public record MusicTrackRecord(
        String id,
        String title,
        String artist,
        String sourceId,
        UUID audioAssetId,
        UUID coverAssetId,
        UUID lyricsAssetId,
        String externalCoverUrl,
        boolean enabled,
        int sortOrder,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
