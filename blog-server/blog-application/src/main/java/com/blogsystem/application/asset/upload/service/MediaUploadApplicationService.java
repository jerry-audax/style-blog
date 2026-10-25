package com.blogsystem.application.asset.upload.service;

import com.blogsystem.application.asset.upload.port.UploadBindingApplicationPort;
import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.upload.gateway.MediaUploadContentVerifier;
import com.blogsystem.domain.asset.upload.gateway.MediaUploadStorageGateway;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.query.MediaUploadPage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCandidate;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadExecutionFence;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFingerprint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadOperationType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadProbe;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadReceipt;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStorageException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTransport;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;

import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Durable upload use cases. Each network call is outside a short database transaction. */
public final class MediaUploadApplicationService {
    public record Submission(MediaUploadOperation operation, boolean created) {}

    private final MediaUploadOperationRepository operations;
    private final Map<MediaUploadTargetType, UploadBindingApplicationPort> bindings;
    private final MediaUploadStorageGateway storage;
    private final MediaUploadContentVerifier verifier;
    private final TransactionOperations transactions;
    private final MediaUploadLeaseRunner leases;
    private final MediaUploadFinalizationService finalization;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final String provider;
    private final MediaObjectKey root;

    public MediaUploadApplicationService(
            MediaUploadOperationRepository operations,
            MediaAssetRepository assets,
            List<UploadBindingApplicationPort> bindings,
            MediaUploadStorageGateway storage,
            MediaUploadContentVerifier verifier,
            TransactionOperations transactions,
            MediaUploadLeaseRunner leases,
            Clock clock,
            Supplier<UUID> ids,
            String provider,
            MediaObjectKey root,
            MediaPublicUrlGateway urls) {
        this.operations = operations;
        this.bindings = new EnumMap<>(MediaUploadTargetType.class);
        for (var binding : bindings)
            if (this.bindings.put(binding.targetType(), binding) != null)
                throw new IllegalArgumentException("Duplicate binding port");
        this.storage = storage;
        this.verifier = verifier;
        this.transactions = transactions;
        this.leases = leases;
        this.clock = clock;
        this.ids = ids;
        this.provider = provider;
        this.root = root;
        this.finalization =
                new MediaUploadFinalizationService(operations, assets, transactions, clock, urls);
    }

    public Submission create(long actor, MediaUploadRequest request) {
        String fingerprint = MediaUploadFingerprint.forUpload(actor, request);
        var existing = operations.findOwned(actor, request.operationId());
        if (existing.isPresent()) return replay(existing.get(), fingerprint);
        UUID candidateAssetId = ids.get();
        var target = binding(request.targetType()).prepareTarget(request, candidateAssetId);
        var operation =
                MediaUploadPolicy.ready(
                        actor, request, target, provider, root, candidateAssetId, clock.instant());
        try {
            return new Submission(transactions.execute(s -> operations.insert(operation)), true);
        } catch (MediaUploadException failure) {
            if (failure.reason() != MediaUploadException.Reason.CONFLICT) throw failure;
            return replay(
                    operations.findOwned(actor, request.operationId()).orElseThrow(() -> failure),
                    fingerprint);
        }
    }

    public MediaUploadOperation get(long actor, UUID id) {
        return operations
                .findOwned(actor, id)
                .orElseThrow(() -> new MediaUploadException(MediaUploadException.Reason.NOT_FOUND));
    }

    public MediaUploadPage history(long actor, int offset, int limit) {
        return operations.findHistory(actor, offset, limit);
    }

    public MediaUploadOperation pause(long actor, UUID id, long version) {
        return transactions.execute(s -> operations.requestPause(actor, id, version));
    }

    public MediaUploadOperation content(long actor, UUID id, MediaUploadContent source) {
        var original = get(actor, id);
        if (original.progress().status() == MediaUploadStatus.SUCCEEDED) return original;
        var claim = transactions.execute(s -> operations.claimContent(actor, id, ids.get()));
        if (claim.isEmpty()) return get(actor, id);
        var claimed = claim.get();
        var fence = fence(claimed);
        return leases.withLease(
                fence,
                guard -> {
                    try {
                        verifier.verify(
                                claimed.intent().file(), claimed.intent().target().slot(), source);
                    } catch (MediaUploadException invalid) {
                        // A bad reselected source must not destroy a known resumable provider
                        // session.
                        if (claimed.progress().providerUploadId() != null)
                            checkpoint(fence, new MediaUploadCheckpoint.Pause());
                        else
                            checkpoint(
                                    fence,
                                    new MediaUploadCheckpoint.Fail(
                                            invalid.code() == null
                                                    ? MediaUploadErrorCode.INVALID_CONTENT
                                                    : invalid.code()));
                        throw invalid;
                    }
                    guard.assertCurrent();
                    var intent = claimed.intent();
                    if (intent.transportMode() == MediaUploadTransport.DIRECT) {
                        dispatch(fence, guard, MediaUploadStage.DIRECT, null);
                        try {
                            return receiveAndFinalize(
                                    fence, guard, storage.uploadDirect(intent, source));
                        } catch (MediaUploadStorageException external) {
                            return externalFailure(fence, external);
                        }
                    }
                    var current = get(actor, id);
                    String session = current.progress().providerUploadId();
                    if (session == null) {
                        dispatch(fence, guard, MediaUploadStage.INIT, null);
                        try {
                            session = storage.beginChunks(intent);
                        } catch (MediaUploadStorageException external) {
                            return externalFailure(fence, external);
                        }
                        current = checkpoint(fence, new MediaUploadCheckpoint.Initialized(session));
                    }
                    if (current.progress().pauseRequested())
                        return checkpoint(fence, new MediaUploadCheckpoint.Pause());
                    try (InputStream input = source.openStream()) {
                        input.skipNBytes(
                                Math.min(
                                        intent.file().size(),
                                        (long) current.progress().confirmedChunks()
                                                * MediaUploadPolicy.CHUNK_BYTES));
                        for (int index = current.progress().confirmedChunks();
                                index < intent.totalChunks();
                                index++) {
                            byte[] chunk =
                                    readChunk(
                                            input,
                                            (int)
                                                    Math.min(
                                                            MediaUploadPolicy.CHUNK_BYTES,
                                                            intent.file().size()
                                                                    - (long) index
                                                                            * MediaUploadPolicy
                                                                                    .CHUNK_BYTES));
                            current = beginOrPause(fence, guard, MediaUploadStage.CHUNK, index);
                            if (current.progress().status() == MediaUploadStatus.PAUSED)
                                return current;
                            try {
                                storage.uploadChunk(intent, session, index, chunk);
                            } catch (MediaUploadStorageException external) {
                                return externalFailure(fence, external);
                            }
                            current =
                                    checkpoint(
                                            fence, new MediaUploadCheckpoint.ChunkConfirmed(index));
                            guard.assertCurrent();
                            if (current.progress().pauseRequested())
                                return checkpoint(fence, new MediaUploadCheckpoint.Pause());
                        }
                    } catch (IOException ignored) {
                        throw new MediaUploadException(
                                MediaUploadException.Reason.UNAVAILABLE,
                                MediaUploadErrorCode.INVALID_CONTENT);
                    }
                    current = beginOrPause(fence, guard, MediaUploadStage.MERGE, null);
                    if (current.progress().status() == MediaUploadStatus.PAUSED) return current;
                    try {
                        return receiveAndFinalize(
                                fence, guard, storage.mergeChunks(intent, session));
                    } catch (MediaUploadStorageException external) {
                        return externalFailure(fence, external);
                    }
                });
    }

    /** Explicit recovery only: at most one probe and one local finalization, never a resend. */
    public MediaUploadOperation recover(long actor, UUID id, long operationVersion) {
        MediaUploadOperation original = get(actor, id);
        if (original.progress().status() == MediaUploadStatus.SUCCEEDED) return original;
        if (original.version() != operationVersion)
            throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
        Optional<MediaUploadOperation> claim =
                transactions.execute(
                        s -> operations.claimRecovery(actor, id, operationVersion, ids.get()));
        if (claim.isEmpty()) return get(actor, id);
        MediaUploadOperation recovered = claim.get();
        // Expired local VERIFY becomes READY/PAUSED without acquiring a transfer lease.
        if (recovered.lease().owner() == null) return recovered;
        MediaUploadExecutionFence fence = fence(recovered);
        return leases.withLease(
                fence,
                guard -> {
                    guard.assertCurrent();
                    if (recovered.progress().status() == MediaUploadStatus.UPLOADED)
                        return finish(fence, recovered);
                    MediaUploadProbe evidence;
                    try {
                        evidence = storage.probe(recovered.intent());
                    } catch (MediaUploadStorageException uncertain) {
                        return checkpoint(
                                fence,
                                new MediaUploadCheckpoint.Unknown(
                                        MediaUploadErrorCode.PROVIDER_UNKNOWN));
                    }
                    guard.assertCurrent();
                    if (!evidence.complete() || evidence.candidates().size() != 1)
                        return checkpoint(
                                fence,
                                new MediaUploadCheckpoint.Unknown(
                                        MediaUploadErrorCode.PROVIDER_UNKNOWN));
                    MediaUploadCandidate candidate = evidence.candidates().get(0);
                    if (candidate.kind() != recovered.intent().target().slot().kind()
                            || !Objects.equals(candidate.size(), recovered.intent().file().size()))
                        return checkpoint(
                                fence,
                                new MediaUploadCheckpoint.Unknown(
                                        MediaUploadErrorCode.PROVIDER_UNKNOWN));
                    return receiveAndFinalize(
                            fence, guard, new MediaUploadReceipt(candidate.objectKey()));
                });
    }

    /** Explicit new-version consent creates a child intent; the original request stays immutable. */
    public Submission bind(
            long actor, UUID sourceId, UUID newId, long expectedVersion, long operationVersion) {
        String fingerprint =
                MediaUploadFingerprint.forBind(
                        actor, sourceId, newId, expectedVersion, operationVersion);
        Optional<MediaUploadOperation> repeated = operations.findOwned(actor, newId);
        if (repeated.isPresent()) return replay(repeated.get(), fingerprint);
        MediaUploadOperation source = get(actor, sourceId);
        var intent = source.intent();
        if (intent.operationType() != MediaUploadOperationType.UPLOAD_AND_BIND
                || intent.target().type() != MediaUploadTargetType.MUSIC_TRACK
                || source.progress().status() != MediaUploadStatus.CONFLICT
                || source.version() != operationVersion
                || source.outcome().resolvedByOperationId() != null)
            throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
        var request =
                new MediaUploadRequest(
                        newId,
                        MediaUploadTargetType.MUSIC_TRACK,
                        intent.target().id(),
                        intent.target().slot(),
                        expectedVersion,
                        intent.file(),
                        null);
        MediaUploadTarget target = binding(request.targetType()).prepareTarget(request, intent.candidateAssetId());
        MediaUploadOperation child =
                MediaUploadPolicy.binding(
                        actor, source, newId, target, operationVersion, clock.instant());
        try {
            transactions.execute(s -> operations.insert(child));
        } catch (MediaUploadException failure) {
            if (failure.reason() != MediaUploadException.Reason.CONFLICT) throw failure;
            return replay(
                    operations.findOwned(actor, newId).orElseThrow(() -> failure), fingerprint);
        }
        return new Submission(recover(actor, newId, 0), true);
    }

    private MediaUploadOperation beginOrPause(
            MediaUploadExecutionFence fence,
            MediaUploadLeaseGuard guard,
            MediaUploadStage stage,
            Integer index) {
        try {
            dispatch(fence, guard, stage, index);
            return get(fence.actorId(), fence.operationId());
        } catch (MediaUploadException conflict) {
            if (conflict.reason() != MediaUploadException.Reason.CONFLICT) throw conflict;
            var latest = get(fence.actorId(), fence.operationId());
            if (latest.progress().pauseRequested()
                    && latest.progress().inFlightChunk() == null
                    && Objects.equals(latest.lease().owner(), fence.workerId())
                    && latest.lease().fencingToken() == fence.fencingToken())
                return checkpoint(fence, new MediaUploadCheckpoint.Pause());
            throw conflict;
        }
    }

    private void dispatch(
            MediaUploadExecutionFence fence,
            MediaUploadLeaseGuard guard,
            MediaUploadStage stage,
            Integer index) {
        guard.assertCurrent();
        checkpoint(fence, new MediaUploadCheckpoint.BeginRequest(stage, index));
        guard.assertCurrent();
    }

    private MediaUploadOperation receiveAndFinalize(
            MediaUploadExecutionFence fence,
            MediaUploadLeaseGuard guard,
            MediaUploadReceipt receipt) {
        guard.assertCurrent();
        var saved = checkpoint(fence, new MediaUploadCheckpoint.Receipt(receipt.objectKey()));
        guard.assertCurrent();
        return finish(fence, saved);
    }

    private MediaUploadOperation finish(
            MediaUploadExecutionFence fence, MediaUploadOperation operation) {
        return finalization.finalizeUpload(
                fence,
                binding(operation.intent().target().type()).prepareCommit(operation.intent()));
    }

    private MediaUploadOperation externalFailure(
            MediaUploadExecutionFence fence, MediaUploadStorageException error) {
        return checkpoint(
                fence,
                error.outcome() == MediaUploadStorageException.Outcome.REJECTED_BEFORE_WRITE
                        ? new MediaUploadCheckpoint.Fail(error.code())
                        : new MediaUploadCheckpoint.Unknown(error.code()));
    }

    private MediaUploadOperation checkpoint(
            MediaUploadExecutionFence fence, MediaUploadCheckpoint command) {
        return transactions.execute(s -> operations.checkpoint(fence, command));
    }

    private UploadBindingApplicationPort binding(MediaUploadTargetType type) {
        var result = bindings.get(type);
        if (result == null) throw new MediaUploadException(MediaUploadException.Reason.UNAVAILABLE);
        return result;
    }

    private static Submission replay(MediaUploadOperation existing, String fingerprint) {
        if (!existing.intent().requestFingerprint().equals(fingerprint))
            throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
        return new Submission(existing, false);
    }

    private static MediaUploadExecutionFence fence(MediaUploadOperation op) {
        return new MediaUploadExecutionFence(
                op.intent().id(),
                op.intent().actorId(),
                op.lease().owner(),
                op.lease().fencingToken());
    }

    private static byte[] readChunk(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int read = 0;
        while (read < length) {
            int n = input.read(bytes, read, Math.min(65536, length - read));
            if (n <= 0) throw new IOException("Source unavailable");
            read += n;
        }
        return bytes;
    }
}
