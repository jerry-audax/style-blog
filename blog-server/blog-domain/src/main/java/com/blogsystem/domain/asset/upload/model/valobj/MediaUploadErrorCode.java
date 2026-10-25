package com.blogsystem.domain.asset.upload.model.valobj;

public enum MediaUploadErrorCode {
    INVALID_CONTENT,
    HASH_MISMATCH,
    PROVIDER_REJECTED,
    PROVIDER_UNKNOWN,
    CHECKPOINT_UNKNOWN,
    TARGET_CHANGED,
    TARGET_MISSING,
    LEASE_LOST,
    SESSION_UNKNOWN,
    DATABASE_UNAVAILABLE
}
