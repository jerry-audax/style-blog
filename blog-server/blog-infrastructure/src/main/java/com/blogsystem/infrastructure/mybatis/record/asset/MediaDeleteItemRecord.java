package com.blogsystem.infrastructure.mybatis.record.asset;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public final class MediaDeleteItemRecord {
    private UUID id;
    private UUID operationId;
    private UUID assetId;
    private String mediaKind;
    private long assetVersion;
    private String deletionScopeSnapshot;
    private String exclusiveEnvironmentSnapshot;
    private Long ownershipConfirmedBySnapshot;
    private OffsetDateTime ownershipConfirmedAtSnapshot;
    private String ownershipNoteSnapshot;
    private String status;
    private String reason;
    private UUID linkedItemId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
