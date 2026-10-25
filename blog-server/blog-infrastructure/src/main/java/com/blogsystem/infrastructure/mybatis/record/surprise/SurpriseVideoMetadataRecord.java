package com.blogsystem.infrastructure.mybatis.record.surprise;

import java.util.UUID;

/** Row returned by the offline surprise-video metadata import mapper. */
public record SurpriseVideoMetadataRecord(
        String id, String title, UUID videoAssetId, boolean enabled, int sortOrder) {}
