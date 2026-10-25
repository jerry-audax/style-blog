package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

/** Narrow commands: adapters may not save a stale aggregate over pause/lease fields. */
public sealed interface MediaUploadCheckpoint {
    record BeginRequest(MediaUploadStage stage, Integer chunkIndex)
            implements MediaUploadCheckpoint {}

    record Initialized(String providerUploadId) implements MediaUploadCheckpoint {}

    record ChunkConfirmed(int chunkIndex) implements MediaUploadCheckpoint {}

    record Receipt(MediaObjectKey objectKey) implements MediaUploadCheckpoint {}

    record Pause() implements MediaUploadCheckpoint {}

    record Unknown(MediaUploadErrorCode code) implements MediaUploadCheckpoint {}

    record Fail(MediaUploadErrorCode code) implements MediaUploadCheckpoint {}

    record Conflict(MediaUploadErrorCode code) implements MediaUploadCheckpoint {}

    record FinalizeStarted() implements MediaUploadCheckpoint {}
}
