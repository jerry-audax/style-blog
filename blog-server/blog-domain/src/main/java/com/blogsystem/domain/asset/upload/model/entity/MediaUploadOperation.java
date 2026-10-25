package com.blogsystem.domain.asset.upload.model.entity;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadOperationType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultFacts;
import com.blogsystem.domain.asset.upload.model.valobj.ImageUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTransport;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable immutable identity and current checkpoint; binary bytes never belong here. */
public record MediaUploadOperation(
        Intent intent,
        Progress progress,
        Lease lease,
        Outcome outcome,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public MediaUploadOperation {
        Objects.requireNonNull(intent);
        Objects.requireNonNull(progress);
        Objects.requireNonNull(lease);
        Objects.requireNonNull(outcome);
        if (version < 0) throw new IllegalArgumentException("Invalid upload version");
        createdAt = MediaUploadPolicy.precision(createdAt);
        updatedAt = MediaUploadPolicy.precision(updatedAt);
        if (updatedAt.isBefore(createdAt))
            throw new IllegalArgumentException("Invalid upload timestamps");
        if (progress.confirmedChunks() > intent.totalChunks()
                || (progress.inFlightChunk() != null
                        && (progress.inFlightChunk() != progress.confirmedChunks()
                                || progress.inFlightChunk() >= intent.totalChunks())))
            throw new IllegalArgumentException("Invalid contiguous checkpoint");
        if (progress.inFlightChunk() != null
                && (progress.stage() != MediaUploadStage.CHUNK
                        || intent.transportMode() != MediaUploadTransport.CHUNKED
                        || progress.providerUploadId() == null))
            throw new IllegalArgumentException("Invalid in-flight chunk");
        if (intent.transportMode() == MediaUploadTransport.DIRECT
                && (progress.providerUploadId() != null || progress.pauseRequested()))
            throw new IllegalArgumentException("Invalid direct-upload checkpoint");
        if (progress.pauseRequested()
                && (progress.stage() != MediaUploadStage.INIT
                        && progress.stage() != MediaUploadStage.CHUNK))
            throw new IllegalArgumentException("Invalid pause request stage");
        var status = progress.status();
        var stage = progress.stage();
        switch (status) {
            case READY -> {
                if (stage != MediaUploadStage.NONE
                        || progress.confirmedChunks() != 0
                        || outcome.confirmedObjectKey() != null
                        || lease.owner() != null)
                    throw new IllegalArgumentException("Invalid ready operation");
            }
            case UPLOADING -> {
                if (stage == MediaUploadStage.NONE
                        || stage == MediaUploadStage.PERSIST
                        || lease.owner() == null)
                    throw new IllegalArgumentException("Invalid uploading operation");
            }
            case PAUSED -> {
                if (stage != MediaUploadStage.CHUNK
                        || intent.transportMode() != MediaUploadTransport.CHUNKED
                        || progress.providerUploadId() == null
                        || progress.inFlightChunk() != null
                        || lease.owner() != null)
                    throw new IllegalArgumentException("Invalid paused operation");
            }
            case UPLOADED, CONFLICT, SUCCEEDED -> {
                if (stage != MediaUploadStage.PERSIST
                        || outcome.confirmedObjectKey() == null
                        || progress.inFlightChunk() != null
                        || progress.confirmedChunks() != intent.totalChunks())
                    throw new IllegalArgumentException("Invalid received operation");
            }
            case UNKNOWN -> {
                if (stage == MediaUploadStage.NONE || stage == MediaUploadStage.VERIFY)
                    throw new IllegalArgumentException("No external outcome to reconcile");
            }
            case FAILED -> {
                if (outcome.confirmedObjectKey() != null
                        || stage == MediaUploadStage.NONE
                        || stage == MediaUploadStage.PERSIST)
                    throw new IllegalArgumentException("Confirmed receipt cannot become failed");
            }
        }
        if ((stage == MediaUploadStage.DIRECT
                        && intent.transportMode() != MediaUploadTransport.DIRECT)
                || ((stage == MediaUploadStage.INIT
                                || stage == MediaUploadStage.CHUNK
                                || stage == MediaUploadStage.MERGE)
                        && intent.transportMode() != MediaUploadTransport.CHUNKED))
            throw new IllegalArgumentException("Transport/stage mismatch");
        if (outcome.confirmedObjectKey() != null)
            outcome.confirmedObjectKey().requireWithin(intent.operationFolder().value());
        if (status == MediaUploadStatus.SUCCEEDED) {
            if (outcome.result() == null
                    || lease.owner() != null
                    || progress.pauseRequested()
                    || outcome.safeErrorCode() != null)
                throw new IllegalArgumentException("Success needs a committed snapshot");
            requireResult(intent, outcome);
        } else if (outcome.result() != null)
            throw new IllegalArgumentException("Only success has a result");
        if (outcome.resolvedByOperationId() != null
                && (status != MediaUploadStatus.CONFLICT
                        || intent.operationType() != MediaUploadOperationType.UPLOAD_AND_BIND
                        || intent.target().type() != MediaUploadTargetType.MUSIC_TRACK
                        || outcome.resolvedByOperationId().equals(intent.id())))
            throw new IllegalArgumentException("Invalid resolved upload");
        if (intent.operationType() == MediaUploadOperationType.BIND_UPLOADED
                && (stage != MediaUploadStage.PERSIST || outcome.confirmedObjectKey() == null))
            throw new IllegalArgumentException("Binding child needs the original receipt");
    }

    private static void requireResult(Intent intent, Outcome outcome) {
        var target = intent.target();
        var result = outcome.result();
        if (result.targetType() != target.type() || !result.targetId().equals(target.id()))
            throw new IllegalArgumentException("Committed target mismatch");
        final com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot resource;
        if (result instanceof ImageUploadResultSnapshot image) {
            if (target.type() != MediaUploadTargetType.IMAGE_CREATE
                    || image.purpose() != target.image().purpose())
                throw new IllegalArgumentException("Committed image purpose mismatch");
            resource = image.resource();
        } else if (result instanceof MediaUploadResultSnapshot legacy) {
            resource = switch (target.slot()) {
                case AUDIO -> legacy.audio();
                case COVER -> legacy.cover();
                case LYRICS -> legacy.lyrics();
                case VIDEO -> legacy.video();
                case IMAGE, AVATAR -> throw new IllegalArgumentException("Image result variant required");
            };
        } else throw new IllegalArgumentException("Unsupported committed result");
        if (resource == null
                || !resource.assetId().equals(intent.candidateAssetId())
                || !resource.provider().equals(intent.provider())
                || !resource.objectKey().equals(outcome.confirmedObjectKey())
                || resource.status() != MediaAssetStatus.ACTIVE
                || resource.kind() != target.slot().kind()
                || !Objects.equals(resource.metadata().fileSize(), intent.file().size())
                || !Objects.equals(resource.metadata().sha256(), intent.file().sourceSha256())
                || !Objects.equals(resource.metadata().mimeType(), intent.file().mediaType()))
            throw new IllegalArgumentException("Committed upload resource mismatch");
        if (target.type() == MediaUploadTargetType.MUSIC_TRACK) {
            if (target.expectedVersion() == Long.MAX_VALUE
                    || result.targetVersion() != target.expectedVersion() + 1)
                throw new IllegalArgumentException("Committed business version mismatch");
        } else if (target.type() == MediaUploadTargetType.SURPRISE_CREATE) {
            var video = (MediaUploadResultSnapshot) result;
            if (video.targetVersion() != 0 || !video.title().equals(target.video().title())
                    || video.enabled() != target.video().enabled()
                    || video.sortOrder() != target.video().sortOrder())
                throw new IllegalArgumentException("Committed video seed mismatch");
        }
    }

    public record Intent(
            UUID id,
            long actorId,
            String requestFingerprint,
            MediaUploadOperationType operationType,
            UUID sourceOperationId,
            MediaUploadTarget target,
            MediaUploadFile file,
            UUID candidateAssetId,
            String provider,
            MediaObjectKey operationFolder,
            MediaUploadTransport transportMode,
            int totalChunks) {
        public Intent {
            Objects.requireNonNull(id);
            Objects.requireNonNull(operationType);
            Objects.requireNonNull(target);
            Objects.requireNonNull(file);
            Objects.requireNonNull(candidateAssetId);
            Objects.requireNonNull(operationFolder);
            Objects.requireNonNull(transportMode);
            provider = MediaUploadPolicy.text(provider, 32, false);
            if (actorId <= 0
                    || requestFingerprint == null
                    || !requestFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid upload identity");
            if ((operationType == MediaUploadOperationType.UPLOAD_AND_BIND
                            && sourceOperationId != null)
                    || (operationType == MediaUploadOperationType.BIND_UPLOADED
                            && (sourceOperationId == null
                                    || sourceOperationId.equals(id)
                                    || target.type() != MediaUploadTargetType.MUSIC_TRACK)))
                throw new IllegalArgumentException("Invalid source operation");
            MediaUploadPolicy.requireFile(target.slot(), file);
            if (target.type() == MediaUploadTargetType.IMAGE_CREATE
                    && !target.id().equals(candidateAssetId.toString()))
                throw new IllegalArgumentException("Image target differs from candidate identity");
            if (totalChunks
                            != (file.size() + MediaUploadPolicy.CHUNK_BYTES - 1)
                                    / MediaUploadPolicy.CHUNK_BYTES
                    || transportMode
                            != (file.size() <= MediaUploadPolicy.CHUNK_BYTES
                                    ? MediaUploadTransport.DIRECT
                                    : MediaUploadTransport.CHUNKED))
                throw new IllegalArgumentException("Invalid chunk plan");
        }

        @Override
        public String toString() {
            return "UploadIntent[id=" + id + ",type=" + operationType + "]";
        }
    }

    public record Progress(
            MediaUploadStatus status,
            MediaUploadStage stage,
            String providerUploadId,
            int confirmedChunks,
            Integer inFlightChunk,
            boolean pauseRequested,
            int executionAttempts,
            int recoveryAttempts,
            int finalizeAttempts) {
        public Progress {
            Objects.requireNonNull(status);
            Objects.requireNonNull(stage);
            if (providerUploadId != null)
                providerUploadId = MediaUploadPolicy.text(providerUploadId, 128, false);
            if (confirmedChunks < 0
                    || (inFlightChunk != null && inFlightChunk < 0)
                    || executionAttempts < 0
                    || recoveryAttempts < 0
                    || finalizeAttempts < 0)
                throw new IllegalArgumentException("Invalid upload counter");
        }

        @Override
        public String toString() {
            return "UploadProgress[status="
                    + status
                    + ",stage="
                    + stage
                    + ",confirmedChunks="
                    + confirmedChunks
                    + "]";
        }
    }

    public record Lease(UUID owner, Instant until, long fencingToken) {
        public Lease {
            if ((owner == null) != (until == null) || fencingToken < 0)
                throw new IllegalArgumentException("Invalid upload lease");
            if (until != null) until = MediaUploadPolicy.precision(until);
        }
    }

    public record Outcome(
            MediaObjectKey confirmedObjectKey,
            MediaUploadErrorCode safeErrorCode,
            MediaUploadResultFacts result,
            UUID resolvedByOperationId) {}

    @Override
    public String toString() {
        return "MediaUploadOperation[id="
                + intent.id()
                + ",status="
                + progress.status()
                + ",version="
                + version
                + "]";
    }
}
