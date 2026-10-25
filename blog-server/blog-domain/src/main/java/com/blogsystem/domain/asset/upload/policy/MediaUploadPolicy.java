package com.blogsystem.domain.asset.upload.policy;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFingerprint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadOperationType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTransport;
import com.blogsystem.domain.music.model.HostedAudio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Domain invariants only. PostgreSQL clock/leases and provider I/O belong to adapters. */
public final class MediaUploadPolicy {
    public static final int CHUNK_BYTES = 16000000;
    private static final Set<String> IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private MediaUploadPolicy() {}

    public static String text(String value, int limit, boolean trim) {
        if (value == null) throw new IllegalArgumentException("Required upload field missing");
        if (trim) value = value.trim();
        if (value.isBlank()
                || value.length() > limit
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid upload field");
        return value;
    }

    public static Instant precision(Instant time) {
        if (time == null) throw new IllegalArgumentException("Upload timestamp required");
        return time.truncatedTo(ChronoUnit.MICROS);
    }

    public static void requireFile(MediaUploadSlot slot, MediaUploadFile file) {
        if (file.size() > slot.maximumBytes())
            throw new MediaUploadException(MediaUploadException.Reason.TOO_LARGE);
        boolean matches =
                switch (slot) {
                    case AUDIO -> HostedAudio.MEDIA_TYPES.contains(file.mediaType());
                    case COVER, IMAGE, AVATAR -> IMAGE_TYPES.contains(file.mediaType());
                    case LYRICS ->
                            file.mediaType().equals("text/plain")
                                    && file.fileName()
                                            .toLowerCase(java.util.Locale.ROOT)
                                            .endsWith(".lrc");
                    case VIDEO -> file.mediaType().startsWith("video/");
                };
        if (!matches) throw new IllegalArgumentException("Upload MIME/slot mismatch");
    }

    public static MediaUploadOperation ready(
            long actorId,
            MediaUploadRequest request,
            MediaUploadTarget target,
            String provider,
            MediaObjectKey root,
            UUID candidateAssetId,
            Instant now) {
        if (request.targetType() != target.type()
                || request.slot() != target.slot()
                || (target.type() == MediaUploadTargetType.MUSIC_TRACK
                        && (!Objects.equals(request.targetId(), target.id())
                                || !Objects.equals(
                                        request.expectedVersion(), target.expectedVersion())))
                || !Objects.equals(request.video(), target.video())
                || !Objects.equals(request.image(), target.image())
                || (target.type() == MediaUploadTargetType.IMAGE_CREATE
                        && !target.id().equals(candidateAssetId.toString())))
            throw new IllegalArgumentException("Frozen target differs from request");
        var transport =
                request.file().size() <= CHUNK_BYTES
                        ? MediaUploadTransport.DIRECT
                        : MediaUploadTransport.CHUNKED;
        requireFile(target.slot(), request.file());
        var intent =
                new MediaUploadOperation.Intent(
                        request.operationId(),
                        actorId,
                        MediaUploadFingerprint.forUpload(actorId, request),
                        MediaUploadOperationType.UPLOAD_AND_BIND,
                        null,
                        target,
                        request.file(),
                        candidateAssetId,
                        provider,
                        new MediaObjectKey(
                                root.value() + "/managed-uploads/" + request.operationId()),
                        transport,
                        (int) ((request.file().size() + CHUNK_BYTES - 1) / CHUNK_BYTES));
        return new MediaUploadOperation(
                intent,
                new MediaUploadOperation.Progress(
                        MediaUploadStatus.READY,
                        MediaUploadStage.NONE,
                        null,
                        0,
                        null,
                        false,
                        0,
                        0,
                        0),
                new MediaUploadOperation.Lease(null, null, 0),
                new MediaUploadOperation.Outcome(null, null, null, null),
                0,
                now,
                now);
    }

    public static MediaUploadOperation binding(
            long actorId,
            MediaUploadOperation source,
            UUID newId,
            MediaUploadTarget refreshedTarget,
            long sourceVersion,
            Instant now) {
        var intent = source.intent();
        var target = intent.target();
        if (intent.actorId() != actorId
                || intent.operationType() != MediaUploadOperationType.UPLOAD_AND_BIND
                || source.version() != sourceVersion
                || source.progress().status() != MediaUploadStatus.CONFLICT
                || source.outcome().resolvedByOperationId() != null
                || source.outcome().confirmedObjectKey() == null
                || target.type() != MediaUploadTargetType.MUSIC_TRACK
                || refreshedTarget.type() != target.type()
                || !target.id().equals(refreshedTarget.id())
                || target.slot() != refreshedTarget.slot()
                || newId == null
                || newId.equals(intent.id())) conflict();
        var child =
                new MediaUploadOperation.Intent(
                        newId,
                        actorId,
                        MediaUploadFingerprint.forBind(
                                actorId,
                                intent.id(),
                                newId,
                                refreshedTarget.expectedVersion(),
                                sourceVersion),
                        MediaUploadOperationType.BIND_UPLOADED,
                        intent.id(),
                        refreshedTarget,
                        intent.file(),
                        intent.candidateAssetId(),
                        intent.provider(),
                        intent.operationFolder(),
                        intent.transportMode(),
                        intent.totalChunks());
        return new MediaUploadOperation(
                child,
                new MediaUploadOperation.Progress(
                        MediaUploadStatus.UPLOADED,
                        MediaUploadStage.PERSIST,
                        null,
                        intent.totalChunks(),
                        null,
                        false,
                        0,
                        0,
                        0),
                new MediaUploadOperation.Lease(null, null, 0),
                new MediaUploadOperation.Outcome(
                        source.outcome().confirmedObjectKey(), null, null, null),
                0,
                now,
                now);
    }

    public static void validateCheckpoint(
            MediaUploadOperation current, MediaUploadCheckpoint command) {
        var p = current.progress();
        var i = current.intent();
        boolean uploading = p.status() == MediaUploadStatus.UPLOADING;
        boolean chunked = i.transportMode() == MediaUploadTransport.CHUNKED;
        if (command instanceof MediaUploadCheckpoint.BeginRequest c) {
            if (!uploading || p.inFlightChunk() != null || p.pauseRequested()) conflict();
            boolean allowed =
                    switch (c.stage()) {
                        case DIRECT ->
                                !chunked
                                        && p.stage() == MediaUploadStage.VERIFY
                                        && p.confirmedChunks() == 0
                                        && c.chunkIndex() == null;
                        case INIT ->
                                chunked
                                        && p.stage() == MediaUploadStage.VERIFY
                                        && p.providerUploadId() == null
                                        && p.confirmedChunks() == 0
                                        && c.chunkIndex() == null;
                        case CHUNK ->
                                chunked
                                        && p.providerUploadId() != null
                                        && (p.stage() == MediaUploadStage.VERIFY
                                                || p.stage() == MediaUploadStage.CHUNK)
                                        && p.confirmedChunks() < i.totalChunks()
                                        && Objects.equals(c.chunkIndex(), p.confirmedChunks());
                        case MERGE ->
                                chunked
                                        && p.providerUploadId() != null
                                        && (p.stage() == MediaUploadStage.VERIFY
                                                || p.stage() == MediaUploadStage.CHUNK)
                                        && p.confirmedChunks() == i.totalChunks()
                                        && c.chunkIndex() == null;
                        default -> false;
                    };
            if (!allowed) conflict();
        } else if (command instanceof MediaUploadCheckpoint.Initialized c) {
            if (!uploading
                    || p.stage() != MediaUploadStage.INIT
                    || p.providerUploadId() != null
                    || !chunked) conflict();
            text(c.providerUploadId(), 128, false);
        } else if (command instanceof MediaUploadCheckpoint.ChunkConfirmed c) {
            if (!uploading
                    || p.stage() != MediaUploadStage.CHUNK
                    || p.inFlightChunk() == null
                    || p.inFlightChunk() != c.chunkIndex()
                    || c.chunkIndex() != p.confirmedChunks()) conflict();
        } else if (command instanceof MediaUploadCheckpoint.Receipt c) {
            if (c.objectKey() == null
                    || (!uploading && p.status() != MediaUploadStatus.UNKNOWN)
                    || (p.stage() != MediaUploadStage.DIRECT
                            && p.stage() != MediaUploadStage.MERGE
                            && p.status() != MediaUploadStatus.UNKNOWN)) conflict();
            try {
                c.objectKey().requireWithin(i.operationFolder().value());
            } catch (IllegalArgumentException invalid) {
                conflict();
            }
        } else if (command instanceof MediaUploadCheckpoint.Pause) {
            if (!uploading
                    || !chunked
                    || (p.stage() != MediaUploadStage.CHUNK && p.stage() != MediaUploadStage.VERIFY)
                    || p.providerUploadId() == null
                    || p.inFlightChunk() != null) conflict();
        } else if (command instanceof MediaUploadCheckpoint.Unknown c) {
            if (c.code() == null
                    || (!uploading && p.status() != MediaUploadStatus.UNKNOWN)
                    || p.stage() == MediaUploadStage.VERIFY
                    || p.stage() == MediaUploadStage.NONE) conflict();
        } else if (command instanceof MediaUploadCheckpoint.Fail c) {
            if (c.code() == null || !uploading || current.outcome().confirmedObjectKey() != null)
                conflict();
        } else if (command instanceof MediaUploadCheckpoint.Conflict c) {
            if (c.code() == null || p.status() != MediaUploadStatus.UPLOADED) conflict();
        } else if (command instanceof MediaUploadCheckpoint.FinalizeStarted) {
            if (p.status() != MediaUploadStatus.UPLOADED
                    || current.outcome().confirmedObjectKey() == null) conflict();
        } else throw new IllegalArgumentException("Unknown checkpoint command");
    }

    /** The input snapshot is checked against actual repository facts, never trusted as an asset identity. */
    public static void validateBindingResource(
            MediaUploadOperation.Intent intent,
            MediaUploadResourceSnapshot resource,
            MediaAsset actual) {
        if (resource == null
                || actual == null
                || !intent.candidateAssetId().equals(resource.assetId())
                || !actual.id().equals(resource.assetId())
                || actual.kind() != intent.target().slot().kind()
                || actual.status() != MediaAssetStatus.ACTIVE
                || resource.kind() != actual.kind()
                || resource.status() != actual.status()
                || !intent.provider().equals(actual.provider())
                || !resource.provider().equals(actual.provider())
                || !resource.objectKey().equals(actual.objectKey())
                || resource.assetVersion() != actual.version()
                || !resource.metadata().equals(actual.metadata())
                || !Objects.equals(intent.file().size(), actual.metadata().fileSize())
                || !Objects.equals(intent.file().sourceSha256(), actual.metadata().sha256())
                || !Objects.equals(intent.file().mediaType(), actual.metadata().mimeType()))
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        try {
            actual.objectKey().requireWithin(intent.operationFolder().value());
        } catch (IllegalArgumentException invalid) {
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        }
    }

    private static void conflict() {
        throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
    }
}
