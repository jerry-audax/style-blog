package com.blogsystem.infrastructure.mybatis.record.asset;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Database-only shape; never used as an HTTP response or logged with its private fields. */
@Getter
@Setter
public final class MediaUploadOperationRecord {
    private UUID id;
    private Long actorId;
    private String requestFingerprint;
    private String operationType;
    private UUID sourceOperationId;
    private String targetType;
    private String targetId;
    private String slot;
    private Long expectedTargetVersion;
    private UUID expectedOldAssetId;
    private String fileName;
    private String mimeType;
    private Long fileSize;
    private String sourceSha256;
    private UUID candidateAssetId;
    private String targetPayload;
    private String provider;
    private String operationFolder;
    private String confirmedObjectKey;
    private String transportMode;
    private String providerUploadId;
    private Integer chunkSize;
    private Integer totalChunks;
    private Integer confirmedChunks;
    private Integer inFlightChunk;
    private String status;
    private String stage;
    private String safeErrorCode;
    private Long version;
    private UUID leaseOwner;
    private OffsetDateTime leaseUntil;
    private Long fencingToken;
    private Boolean pauseRequested;
    private Integer executionAttempts;
    private Integer recoveryAttempts;
    private Integer finalizeAttempts;
    private String resultSnapshot;
    private UUID resolvedByOperationId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Override
    public String toString() {
        return "MediaUploadOperationRecord[private fields redacted]";
    }
}
