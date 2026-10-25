package com.blogsystem.domain.asset.inventory.model.query;

/** Facts prepared before commit; consumers must still distinguish an unknown commit outcome. */
public record MediaImportReceipt(
        String mode,
        String database,
        String schema,
        int tablesInitialized,
        int assetsCreated,
        int assetsPreserved,
        int tracksCreated,
        int tracksPreserved,
        int videosCreated,
        int videosPreserved,
        int videosSkipped,
        int missingBindings,
        boolean videoSnapshotMissing,
        boolean runtimeRepositorySwitched) {}
