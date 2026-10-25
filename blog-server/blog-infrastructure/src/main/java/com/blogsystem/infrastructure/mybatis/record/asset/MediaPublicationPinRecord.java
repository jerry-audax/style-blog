package com.blogsystem.infrastructure.mybatis.record.asset;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public final class MediaPublicationPinRecord {
    private String environmentId;
    private String publisherId;
    private String releaseId;
    private String manifestDigest;
    private UUID[] assetIds;
    private String status;
    private long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime releasedAt;
}
