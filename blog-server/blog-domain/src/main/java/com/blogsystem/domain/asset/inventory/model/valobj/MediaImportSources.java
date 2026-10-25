package com.blogsystem.domain.asset.inventory.model.valobj;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** Explicit offline input locations; never carries a connection or credentials. */
public record MediaImportSources(
        String database,
        String schema,
        Path report,
        Path musicCatalog,
        Path surpriseCatalog,
        Path playlist,
        Path ddl,
        Path backup) {
    public MediaImportSources {
        if (!Set.of("blog_system", "blog_media_test").contains(database))
            throw new IllegalArgumentException("Invalid import catalog");
        if (!("public".equals(schema)
                || (database.equals("blog_media_test")
                        && schema != null
                        && schema.matches("media_it_[0-9a-f]{32}"))))
            throw new IllegalArgumentException("Invalid import schema");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(musicCatalog, "musicCatalog");
        Objects.requireNonNull(surpriseCatalog, "surpriseCatalog");
    }
}
