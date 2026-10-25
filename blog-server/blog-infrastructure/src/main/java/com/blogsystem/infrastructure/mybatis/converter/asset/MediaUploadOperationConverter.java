package com.blogsystem.infrastructure.mybatis.converter.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadOperationType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultFacts;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadImageSeed;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTransport;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadVideoSeed;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import com.blogsystem.infrastructure.mybatis.codec.asset.MediaUploadSnapshotCodec;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaUploadOperationRecord;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Explicit PO/domain mapping; JSONB serialization remains outside the domain. */
public final class MediaUploadOperationConverter {
    private final MediaUploadSnapshotCodec snapshots;
    private final ObjectMapper videoJson =
            new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public MediaUploadOperationConverter(MediaUploadSnapshotCodec snapshots) {
        this.snapshots = snapshots;
    }

    public MediaUploadOperationRecord toRecord(MediaUploadOperation op) {
        var r = new MediaUploadOperationRecord();
        var i = op.intent();
        var p = op.progress();
        var t = i.target();
        r.setId(i.id());
        r.setActorId(i.actorId());
        r.setRequestFingerprint(i.requestFingerprint());
        r.setOperationType(i.operationType().name());
        r.setSourceOperationId(i.sourceOperationId());
        r.setTargetType(t.type().name());
        r.setTargetId(t.id());
        r.setSlot(t.slot().name());
        r.setExpectedTargetVersion(t.expectedVersion());
        r.setExpectedOldAssetId(t.expectedOldAssetId());
        r.setFileName(i.file().fileName());
        r.setMimeType(i.file().mediaType());
        r.setFileSize(i.file().size());
        r.setSourceSha256(i.file().sourceSha256());
        r.setCandidateAssetId(i.candidateAssetId());
        r.setTargetPayload(t.type() == MediaUploadTargetType.IMAGE_CREATE ? image(t.image()) : video(t.video()));
        r.setProvider(i.provider());
        r.setOperationFolder(i.operationFolder().value());
        r.setConfirmedObjectKey(
                op.outcome().confirmedObjectKey() == null
                        ? null
                        : op.outcome().confirmedObjectKey().value());
        r.setTransportMode(i.transportMode().name());
        r.setProviderUploadId(p.providerUploadId());
        r.setChunkSize(MediaUploadPolicy.CHUNK_BYTES);
        r.setTotalChunks(i.totalChunks());
        r.setConfirmedChunks(p.confirmedChunks());
        r.setInFlightChunk(p.inFlightChunk());
        r.setStatus(p.status().name());
        r.setStage(p.stage().name());
        r.setSafeErrorCode(
                op.outcome().safeErrorCode() == null ? null : op.outcome().safeErrorCode().name());
        r.setVersion(op.version());
        r.setLeaseOwner(op.lease().owner());
        r.setLeaseUntil(op.lease().until() == null ? null : time(op.lease().until()));
        r.setFencingToken(op.lease().fencingToken());
        r.setPauseRequested(p.pauseRequested());
        r.setExecutionAttempts(p.executionAttempts());
        r.setRecoveryAttempts(p.recoveryAttempts());
        r.setFinalizeAttempts(p.finalizeAttempts());
        r.setResultSnapshot(
                op.outcome().result() == null ? null : snapshots.encode(op.outcome().result()));
        r.setResolvedByOperationId(op.outcome().resolvedByOperationId());
        r.setCreatedAt(time(op.createdAt()));
        r.setUpdatedAt(time(op.updatedAt()));
        return r;
    }

    public MediaUploadOperation toDomain(MediaUploadOperationRecord r) {
        if (r == null) return null;
        if (r.getChunkSize() != MediaUploadPolicy.CHUNK_BYTES)
            throw new IllegalArgumentException("Invalid stored chunk size");
        var type = MediaUploadTargetType.valueOf(r.getTargetType());
        var target =
                new MediaUploadTarget(
                        type,
                        r.getTargetId(),
                        MediaUploadSlot.valueOf(r.getSlot()),
                        r.getExpectedTargetVersion(),
                        r.getExpectedOldAssetId(),
                        type == MediaUploadTargetType.SURPRISE_CREATE
                                ? video(r.getTargetPayload())
                                : null,
                        type == MediaUploadTargetType.IMAGE_CREATE ? image(r.getTargetPayload()) : null);
        var intent =
                new MediaUploadOperation.Intent(
                        r.getId(),
                        r.getActorId(),
                        r.getRequestFingerprint().trim(),
                        MediaUploadOperationType.valueOf(r.getOperationType()),
                        r.getSourceOperationId(),
                        target,
                        new MediaUploadFile(
                                r.getFileName(),
                                r.getMimeType(),
                                r.getFileSize(),
                                r.getSourceSha256().trim()),
                        r.getCandidateAssetId(),
                        r.getProvider(),
                        new MediaObjectKey(r.getOperationFolder()),
                        MediaUploadTransport.valueOf(r.getTransportMode()),
                        r.getTotalChunks());
        var progress =
                new MediaUploadOperation.Progress(
                        MediaUploadStatus.valueOf(r.getStatus()),
                        MediaUploadStage.valueOf(r.getStage()),
                        r.getProviderUploadId(),
                        r.getConfirmedChunks(),
                        r.getInFlightChunk(),
                        r.getPauseRequested(),
                        r.getExecutionAttempts(),
                        r.getRecoveryAttempts(),
                        r.getFinalizeAttempts());
        var lease =
                new MediaUploadOperation.Lease(
                        r.getLeaseOwner(),
                        r.getLeaseUntil() == null ? null : r.getLeaseUntil().toInstant(),
                        r.getFencingToken());
        var outcome =
                new MediaUploadOperation.Outcome(
                        r.getConfirmedObjectKey() == null
                                ? null
                                : new MediaObjectKey(r.getConfirmedObjectKey()),
                        r.getSafeErrorCode() == null
                                ? null
                                : MediaUploadErrorCode.valueOf(r.getSafeErrorCode()),
                        r.getResultSnapshot() == null
                                ? null
                                : snapshots.decode(r.getResultSnapshot()),
                        r.getResolvedByOperationId());
        return new MediaUploadOperation(
                intent,
                progress,
                lease,
                outcome,
                r.getVersion(),
                r.getCreatedAt().toInstant(),
                r.getUpdatedAt().toInstant());
    }

    public String encodeResult(MediaUploadResultFacts result) {
        return snapshots.encode(result);
    }

    private static OffsetDateTime time(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String video(MediaUploadVideoSeed value) {
        if (value == null) return "{}";
        try {
            return videoJson.writeValueAsString(value);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid video seed");
        }
    }

    private MediaUploadVideoSeed video(String value) {
        try {
            return videoJson.readValue(value, MediaUploadVideoSeed.class);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid stored video seed");
        }
    }

    private String image(MediaUploadImageSeed value) {
        try {
            return videoJson.writeValueAsString(value);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid image seed");
        }
    }

    private MediaUploadImageSeed image(String value) {
        try {
            return videoJson.readValue(value, MediaUploadImageSeed.class);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid stored image seed");
        }
    }
}
