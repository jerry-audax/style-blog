package com.blogsystem.domain.asset.inventory.repository;

import java.nio.file.Path;

/** Offline import only; preparation must participate in the caller's transaction. */
public interface MediaImportSchemaRepository {
    boolean prepare(String database, String schema, Path ddl);
}
