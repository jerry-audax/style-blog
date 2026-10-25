package com.blogsystem.domain.asset.upload.model.valobj;

public enum MediaUploadStage {
    NONE,
    VERIFY,
    DIRECT,
    INIT,
    CHUNK,
    MERGE,
    PERSIST
}
