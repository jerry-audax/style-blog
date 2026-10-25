package com.blogsystem.domain.asset.inventory.model.valobj;

/** Result of comparing provider inventory with application references. */
public enum MediaReconciliationStatus {
    MATCHED,
    MISSING,
    NEW,
    CONFLICT
}
