package com.blogsystem.domain.asset.upload.model.valobj;

import java.util.Objects;

/** Safe classification only; deliberately carries no provider body or exception cause. */
public final class MediaUploadStorageException extends RuntimeException {
    public enum Outcome {
        REJECTED_BEFORE_WRITE,
        UNKNOWN
    }

    private final Outcome outcome;
    private final MediaUploadErrorCode code;

    public MediaUploadStorageException(Outcome outcome, MediaUploadErrorCode code) {
        super("媒体存储请求未确认");
        this.outcome = Objects.requireNonNull(outcome);
        this.code = Objects.requireNonNull(code);
    }

    public Outcome outcome() {
        return outcome;
    }

    public MediaUploadErrorCode code() {
        return code;
    }
}
