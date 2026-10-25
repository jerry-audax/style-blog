package com.blogsystem.domain.asset.upload.repository;

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.query.MediaUploadPage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCheckpoint;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadExecutionFence;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultFacts;

import java.util.Optional;
import java.util.UUID;

/** Owned durable operations. All writes join the caller's short transaction. */
public interface MediaUploadOperationRepository {
    MediaUploadOperation insert(MediaUploadOperation operation);

    Optional<MediaUploadOperation> findOwned(long actorId, UUID id);

    /** Fail closed if an adapter cannot check a pending cover before another provider write. */
    default Optional<MediaUploadOperation> findUnresolvedMusicCover(long actorId, String trackId) {
        throw new com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException(
                com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException.Reason.UNAVAILABLE);
    }

    Optional<MediaUploadOperation> findOwnedForUpdate(long actorId, UUID id);

    MediaUploadPage findHistory(long actorId, int offset, int limit);

    Optional<MediaUploadOperation> claimContent(long actorId, UUID id, UUID workerId);

    Optional<MediaUploadOperation> claimRecovery(
            long actorId, UUID id, long expectedVersion, UUID workerId);

    boolean renewLease(MediaUploadExecutionFence fence);

    MediaUploadOperation requestPause(long actorId, UUID id, long expectedVersion);

    MediaUploadOperation checkpoint(
            MediaUploadExecutionFence fence, MediaUploadCheckpoint checkpoint);

    MediaUploadOperation complete(
            MediaUploadExecutionFence fence, MediaUploadResultFacts result);

    void resolveSource(MediaUploadExecutionFence childFence, UUID sourceId, UUID childId);
}
