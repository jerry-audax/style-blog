package com.blogsystem.domain.asset.metadata.model.valobj;

/** The resource identity or provider location already exists. */
public class DuplicateMediaAssetException extends RuntimeException {
    public DuplicateMediaAssetException(String message, Throwable cause) {
        super(message, cause);
    }
}
