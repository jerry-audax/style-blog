package com.blogsystem.domain.asset.metadata.model.valobj;

/** Safe cross-context read failure; never exposes database details. */
public final class MediaAssetReadException extends RuntimeException {
    public MediaAssetReadException() {
        super("媒体资源暂不可用");
    }
}
