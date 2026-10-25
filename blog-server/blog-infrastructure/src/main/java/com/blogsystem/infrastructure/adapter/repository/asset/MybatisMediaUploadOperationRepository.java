package com.blogsystem.infrastructure.adapter.repository.asset;

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.query.MediaUploadPage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadExecutionFence;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadOperationType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultFacts;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTransport;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaUploadOperationConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaUploadOperationMapper;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** No provider I/O or autonomous commits. Every mutation joins the caller's transaction. */
public final class MybatisMediaUploadOperationRepository implements MediaUploadOperationRepository {
    private final MediaUploadOperationMapper mapper;
    private final MediaUploadOperationConverter mapping;

    public MybatisMediaUploadOperationRepository(
            MediaUploadOperationMapper mapper, MediaUploadOperationConverter mapping) {
        this.mapper = Objects.requireNonNull(mapper);
        this.mapping = Objects.requireNonNull(mapping);
    }

    @Override
    public MediaUploadOperation insert(MediaUploadOperation op) {
        var i = op.intent();
        var p = op.progress();
        if (op.version() != 0
                || op.lease().fencingToken() != 0
                || op.lease().owner() != null
                || p.executionAttempts() != 0
                || p.recoveryAttempts() != 0
                || p.finalizeAttempts() != 0
                || (i.operationType() == MediaUploadOperationType.UPLOAD_AND_BIND
                        && p.status() != MediaUploadStatus.READY)
                || (i.operationType() == MediaUploadOperationType.BIND_UPLOADED
                        && p.status() != MediaUploadStatus.UPLOADED))
            throw new IllegalArgumentException("Only new upload intents may be inserted");
        return guarded(
                () -> {
                    if (i.operationType() == MediaUploadOperationType.BIND_UPLOADED) {
                        var source = ownedLocked(i.actorId(), i.sourceOperationId());
                        requireSource(source, op);
                    }
                    try {
                        if (mapper.insert(mapping.toRecord(op)) != 1) conflict();
                    } catch (RuntimeException failure) {
                        if (unique(failure)) conflict();
                        throw failure;
                    }
                    return op;
                });
    }

    @Override
    public Optional<MediaUploadOperation> findOwned(long actorId, UUID id) {
        identity(actorId, id);
        return guarded(
                () -> Optional.ofNullable(mapping.toDomain(mapper.selectOwned(actorId, id))));
    }

    @Override
    public Optional<MediaUploadOperation> findUnresolvedMusicCover(long actorId, String trackId) {
        if (actorId <= 0 || trackId == null || trackId.isBlank() || trackId.length() > 64)
            throw new IllegalArgumentException("Invalid music cover lookup");
        return guarded(() -> Optional.ofNullable(mapping.toDomain(mapper.unresolvedMusicCover(actorId, trackId))));
    }

    @Override
    public Optional<MediaUploadOperation> findOwnedForUpdate(long actorId, UUID id) {
        identity(actorId, id);
        return guarded(() -> Optional.ofNullable(mapping.toDomain(mapper.lockOwned(actorId, id))));
    }

    @Override
    public MediaUploadPage findHistory(long actorId, int offset, int limit) {
        if (actorId <= 0 || offset < 0 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid owned upload page");
        return guarded(
                () ->
                        new MediaUploadPage(
                                mapper.history(actorId, offset, limit).stream()
                                        .map(mapping::toDomain)
                                        .toList(),
                                mapper.countOwned(actorId),
                                offset,
                                limit));
    }

    @Override
    public Optional<MediaUploadOperation> claimContent(long actorId, UUID id, UUID workerId) {
        identity(actorId, id);
        Objects.requireNonNull(workerId);
        return guarded(
                () ->
                        mapper.claimContent(actorId, id, workerId) == 1
                                ? Optional.of(owned(actorId, id))
                                : Optional.empty());
    }

    @Override
    public Optional<MediaUploadOperation> claimRecovery(
            long actorId, UUID id, long expectedVersion, UUID workerId) {
        identity(actorId, id);
        Objects.requireNonNull(workerId);
        version(expectedVersion);
        return guarded(
                () ->
                        mapper.claimRecovery(actorId, id, expectedVersion, workerId) == 1
                                ? Optional.of(owned(actorId, id))
                                : Optional.empty());
    }

    @Override
    public boolean renewLease(MediaUploadExecutionFence fence) {
        return guarded(() -> mapper.renew(fence) == 1);
    }

    @Override
    public MediaUploadOperation requestPause(long actorId, UUID id, long expectedVersion) {
        identity(actorId, id);
        version(expectedVersion);
        return guarded(
                () -> {
                    var current = ownedLocked(actorId, id);
                    var p = current.progress();
                    if (current.version() != expectedVersion
                            || p.status() != MediaUploadStatus.UPLOADING
                            || current.intent().transportMode() != MediaUploadTransport.CHUNKED
                            || (p.stage() != MediaUploadStage.INIT
                                    && p.stage() != MediaUploadStage.CHUNK)) conflict();
                    if (p.pauseRequested()) return current;
                    if (mapper.requestPause(actorId, id, expectedVersion) != 1) conflict();
                    return owned(actorId, id);
                });
    }

    @Override
    public MediaUploadOperation checkpoint(
            MediaUploadExecutionFence fence, MediaUploadCheckpoint command) {
        return guarded(
                () -> {
                    var current = fencedLocked(fence);
                    MediaUploadPolicy.validateCheckpoint(current, command);
                    long v = current.version();
                    int changed;
                    if (command instanceof MediaUploadCheckpoint.BeginRequest c)
                        changed = mapper.begin(fence, v, c.stage().name(), c.chunkIndex());
                    else if (command instanceof MediaUploadCheckpoint.Initialized c)
                        changed = mapper.initialized(fence, v, c.providerUploadId());
                    else if (command instanceof MediaUploadCheckpoint.ChunkConfirmed)
                        changed = mapper.chunkConfirmed(fence, v);
                    else if (command instanceof MediaUploadCheckpoint.Receipt c)
                        changed = mapper.receipt(fence, v, c.objectKey().value());
                    else if (command instanceof MediaUploadCheckpoint.Pause)
                        changed = mapper.pause(fence, v);
                    else if (command instanceof MediaUploadCheckpoint.Unknown c)
                        changed = mapper.unknown(fence, v, c.code().name());
                    else if (command instanceof MediaUploadCheckpoint.Fail c)
                        changed = mapper.fail(fence, v, c.code().name());
                    else if (command instanceof MediaUploadCheckpoint.Conflict c)
                        changed = mapper.conflict(fence, v, c.code().name());
                    else if (command instanceof MediaUploadCheckpoint.FinalizeStarted)
                        changed = mapper.finalizeStarted(fence, v);
                    else throw new IllegalArgumentException("Unknown upload checkpoint");
                    if (changed != 1) conflict();
                    return owned(fence.actorId(), fence.operationId());
                });
    }

    @Override
    public MediaUploadOperation complete(
            MediaUploadExecutionFence fence, MediaUploadResultFacts result) {
        return guarded(
                () -> {
                    var current = fencedLocked(fence);
                    var p = current.progress();
                    if (p.status() != MediaUploadStatus.UPLOADED) conflict();
                    // Validate identity/resource/version consistency and serialization before any
                    // success write.
                    new MediaUploadOperation(
                            current.intent(),
                            new MediaUploadOperation.Progress(
                                    MediaUploadStatus.SUCCEEDED,
                                    MediaUploadStage.PERSIST,
                                    p.providerUploadId(),
                                    p.confirmedChunks(),
                                    null,
                                    false,
                                    p.executionAttempts(),
                                    p.recoveryAttempts(),
                                    p.finalizeAttempts()),
                            new MediaUploadOperation.Lease(
                                    null, null, current.lease().fencingToken()),
                            new MediaUploadOperation.Outcome(
                                    current.outcome().confirmedObjectKey(), null, result, null),
                            current.version() + 1,
                            current.createdAt(),
                            current.updatedAt());
                    String snapshot = mapping.encodeResult(result);
                    if (mapper.complete(fence, current.version(), snapshot) != 1) conflict();
                    return owned(fence.actorId(), fence.operationId());
                });
    }

    @Override
    public void resolveSource(MediaUploadExecutionFence childFence, UUID sourceId, UUID childId) {
        guarded(
                () -> {
                    if (!childFence.operationId().equals(childId)) conflict();
                    // The source is always locked before the child, including direct repository
                    // callers.
                    var source = ownedLocked(childFence.actorId(), sourceId);
                    var child = fencedLocked(childFence);
                    requireSource(source, child);
                    if (source.version() == Long.MAX_VALUE
                            || mapper.resolve(childFence, sourceId, childId, source.version()) != 1)
                        conflict();
                    return null;
                });
    }

    private MediaUploadOperation owned(long actorId, UUID id) {
        var result = mapping.toDomain(mapper.selectOwned(actorId, id));
        if (result == null) throw new MediaUploadException(MediaUploadException.Reason.NOT_FOUND);
        return result;
    }

    private MediaUploadOperation ownedLocked(long actorId, UUID id) {
        var result = mapping.toDomain(mapper.lockOwned(actorId, id));
        if (result == null) throw new MediaUploadException(MediaUploadException.Reason.NOT_FOUND);
        return result;
    }

    private MediaUploadOperation fencedLocked(MediaUploadExecutionFence fence) {
        Objects.requireNonNull(fence);
        var value = mapping.toDomain(mapper.lockFenced(fence));
        if (value == null || value.version() == Long.MAX_VALUE) conflict();
        return value;
    }

    private static void requireSource(MediaUploadOperation source, MediaUploadOperation child) {
        var s = source.intent();
        var c = child.intent();
        if (s.operationType() != MediaUploadOperationType.UPLOAD_AND_BIND
                || source.progress().status() != MediaUploadStatus.CONFLICT
                || source.outcome().resolvedByOperationId() != null
                || c.operationType() != MediaUploadOperationType.BIND_UPLOADED
                || !s.id().equals(c.sourceOperationId())
                || s.actorId() != c.actorId()
                || s.target().type() != MediaUploadTargetType.MUSIC_TRACK
                || c.target().type() != s.target().type()
                || !s.target().id().equals(c.target().id())
                || c.target().slot() != s.target().slot()
                || !s.candidateAssetId().equals(c.candidateAssetId())
                || !s.provider().equals(c.provider())
                || !s.operationFolder().equals(c.operationFolder())
                || !s.file().equals(c.file())
                || !Objects.equals(
                        source.outcome().confirmedObjectKey(), child.outcome().confirmedObjectKey())
                || child.progress().status() != MediaUploadStatus.UPLOADED) conflict();
    }

    private static void identity(long actor, UUID id) {
        if (actor <= 0 || id == null) throw new IllegalArgumentException("Invalid upload identity");
    }

    private static void version(long value) {
        if (value < 0) throw new IllegalArgumentException("Invalid upload version");
    }

    private static void conflict() {
        throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
    }

    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (MediaUploadException safe) {
            throw safe;
        } catch (RuntimeException failure) {
            throw new MediaUploadException(MediaUploadException.Reason.UNAVAILABLE);
        }
    }

    private static boolean unique(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        return false;
    }
}
