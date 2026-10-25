package com.blogsystem.infrastructure.mybatis.record.asset;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public final class MediaDeleteOperationRecord {
    private UUID id;
    private long actorId;
    private String idempotencyKey;
    private String fingerprint;
    private String targetType;
    private String targetId;
    private String mediaSlot;
    private long originalVersion;
    private OffsetDateTime committedAt;
}
