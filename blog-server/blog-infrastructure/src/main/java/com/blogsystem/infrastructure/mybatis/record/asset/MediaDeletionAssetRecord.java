package com.blogsystem.infrastructure.mybatis.record.asset;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
public final class MediaDeletionAssetRecord extends MediaAssetRecord {
    private String deletionScope;
    private String exclusiveEnvironment;
    private Long ownershipConfirmedBy;
    private OffsetDateTime ownershipConfirmedAt;
    private String ownershipNote;
}
