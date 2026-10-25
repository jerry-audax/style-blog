package com.blogsystem.domain.asset.metadata.model.valobj;

/** The optimistic-lock snapshot no longer matches the stored resource. */
public class MediaAssetWriteConflictException extends RuntimeException {
    public MediaAssetWriteConflictException(String message) {
        super(message);
    }
}
