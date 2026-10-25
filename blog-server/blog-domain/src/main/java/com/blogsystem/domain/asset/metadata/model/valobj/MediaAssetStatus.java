package com.blogsystem.domain.asset.metadata.model.valobj;

/** Lifecycle states used by later media deletion workflows. */
public enum MediaAssetStatus {
    ACTIVE,
    DELETE_PENDING,
    DELETE_FAILED,
    DELETED
}
