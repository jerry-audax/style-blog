package com.blogsystem.domain.asset.upload.model.valobj;

/** Closed variants of durably committed upload facts, not mutable catalogue projections. */
public sealed interface MediaUploadResultFacts
        permits MediaUploadResultSnapshot, ImageUploadResultSnapshot {
    MediaUploadTargetType targetType();
    String targetId();
    long targetVersion();
}
