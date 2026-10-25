package com.blogsystem.infrastructure.mybatis.record.surprise;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SurpriseVideoRecord(
        String id,
        String title,
        UUID videoAssetId,
        boolean enabled,
        int sortOrder,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
