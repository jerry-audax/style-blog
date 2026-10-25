package com.blogsystem.application.asset.upload.service;

import com.blogsystem.application.asset.upload.assembler.MediaUploadViewProjector;
import com.blogsystem.application.asset.upload.port.UploadBindingPreparation;
import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.DuplicateMediaAssetException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadExecutionFence;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;

import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.Objects;

/** No provider calls. Asset, single business CAS and frozen success commit together. */
public final class MediaUploadFinalizationService {
    private final MediaUploadOperationRepository operations;
    private final MediaAssetRepository assets;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final MediaUploadViewProjector projection;

    public MediaUploadFinalizationService(
            MediaUploadOperationRepository operations,
            MediaAssetRepository assets,
            TransactionOperations transactions,
            Clock clock,
            MediaPublicUrlGateway urls) {
        this.operations = operations;
        this.assets = assets;
        this.transactions = transactions;
        this.clock = clock;
        this.projection = new MediaUploadViewProjector(urls);
    }

    public MediaUploadOperation finalizeUpload(
            MediaUploadExecutionFence fence, UploadBindingPreparation prepared) {
        // Durably count the attempt before the business transaction; rollback must not erase it.
        transactions.execute(
                s -> operations.checkpoint(fence, new MediaUploadCheckpoint.FinalizeStarted()));
        try {
            return transactions.execute(
                    s -> {
                        var observedOperation =
                                operations
                                        .findOwned(fence.actorId(), fence.operationId())
                                        .orElseThrow(
                                                () ->
                                                        new MediaUploadException(
                                                                MediaUploadException.Reason
                                                                        .NOT_FOUND));
                        var intent = observedOperation.intent();
                        var file = intent.file();
                        var now = clock.instant();
                        var expected =
                                MediaAsset.register(
                                        intent.candidateAssetId(),
                                        intent.provider(),
                                        observedOperation.outcome().confirmedObjectKey(),
                                        intent.target().slot().kind(),
                                        new MediaMetadata(
                                                file.fileName(),
                                                file.mediaType(),
                                                file.size(),
                                                file.sourceSha256(),
                                                null,
                                                null,
                                                null),
                                        now);
                        var byId = assets.findById(expected.id());
                        var byLocation =
                                assets.findByLocation(expected.provider(), expected.objectKey());
                        MediaAsset asset;
                        if (byId.isPresent() || byLocation.isPresent()) {
                            asset =
                                    byId.orElseThrow(
                                            () ->
                                                    new MediaUploadException(
                                                            MediaUploadException.Reason.CONFLICT));
                            if (byLocation.isEmpty()
                                    || !byLocation.get().id().equals(asset.id())
                                    || !sameResource(expected, asset))
                                throw new MediaUploadException(
                                        MediaUploadException.Reason.CONFLICT);
                        } else {
                            try {
                                asset = assets.insert(expected);
                            } catch (DuplicateMediaAssetException collision) {
                                // 并发事务已占用唯一定位；退出已中止事务后仅报告冲突，不继续查询。
                                throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
                            }
                        }
                        var resource =
                                new MediaUploadResourceSnapshot(
                                        asset.id(),
                                        asset.provider(),
                                        asset.objectKey(),
                                        asset.kind(),
                                        asset.status(),
                                        asset.version(),
                                        asset.metadata());
                        var result = prepared.commit(resource, now);
                        var committedAsset =
                                assets.findById(expected.id())
                                        .orElseThrow(
                                                () ->
                                                        new MediaUploadException(
                                                                MediaUploadException.Reason.CONFLICT));
                        if (!sameResource(expected, committedAsset))
                            throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);

                        // 先由业务绑定取得资源锁并暂存业务写入，再按来源到当前操作加锁、复核租约；
                        // 过期执行者会回滚候选资源与业务变更。
                        if (intent.sourceOperationId() != null)
                            operations
                                    .findOwnedForUpdate(fence.actorId(), intent.sourceOperationId())
                                    .orElseThrow(
                                            () ->
                                                    new MediaUploadException(
                                                            MediaUploadException.Reason.NOT_FOUND));
                        var operation =
                                operations
                                        .findOwnedForUpdate(fence.actorId(), fence.operationId())
                                        .orElseThrow(
                                                () ->
                                                        new MediaUploadException(
                                                                MediaUploadException.Reason.NOT_FOUND));
                        if (!operation.intent().equals(intent)
                                || !Objects.equals(
                                        operation.outcome().confirmedObjectKey(),
                                        observedOperation.outcome().confirmedObjectKey()))
                            throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
                        if (operation.progress().status() != MediaUploadStatus.UPLOADED
                                || !Objects.equals(operation.lease().owner(), fence.workerId())
                                || operation.lease().fencingToken() != fence.fencingToken()
                                || !operations.renewLease(fence))
                            throw new MediaUploadException(
                                    MediaUploadException.Reason.CONFLICT,
                                    MediaUploadErrorCode.LEASE_LOST);
                        projection.validate(result);
                        if (intent.sourceOperationId() != null)
                            operations.resolveSource(
                                    fence, intent.sourceOperationId(), intent.id());
                        return operations.complete(fence, result);
                    });
        } catch (RuntimeException failure) {
            // A reported commit error is not proof of rollback: read durable truth before any
            // decision.
            var observed =
                    operations
                            .findOwned(fence.actorId(), fence.operationId())
                            .orElseThrow(
                                    () ->
                                            new MediaUploadException(
                                                    MediaUploadException.Reason.NOT_FOUND));
            if (observed.progress().status() == MediaUploadStatus.SUCCEEDED) return observed;
            if (failure instanceof MediaUploadException safe
                    && (safe.reason() == MediaUploadException.Reason.CONFLICT
                            || safe.reason() == MediaUploadException.Reason.NOT_FOUND)) {
                if (safe.code() == MediaUploadErrorCode.LEASE_LOST) throw safe;
                return transactions.execute(
                        s ->
                                operations.checkpoint(
                                        fence,
                                        new MediaUploadCheckpoint.Conflict(
                                                safe.reason()
                                                                == MediaUploadException.Reason
                                                                        .NOT_FOUND
                                                        ? MediaUploadErrorCode.TARGET_MISSING
                                                        : MediaUploadErrorCode.TARGET_CHANGED)));
            }
            throw new MediaUploadException(
                    MediaUploadException.Reason.UNAVAILABLE,
                    MediaUploadErrorCode.DATABASE_UNAVAILABLE);
        }
    }

    private static boolean sameResource(MediaAsset expected, MediaAsset actual) {
        return actual.id().equals(expected.id())
                && actual.provider().equals(expected.provider())
                && actual.objectKey().equals(expected.objectKey())
                && actual.kind() == expected.kind()
                && actual.metadata().equals(expected.metadata())
                && actual.status() == MediaAssetStatus.ACTIVE
                && actual.version() == 0;
    }
}
