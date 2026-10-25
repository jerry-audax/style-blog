package com.blogsystem.domain.asset.inventory.model.query;

import java.util.List;

public record MediaCatalogSnapshot(
        String source,
        String path,
        boolean available,
        String sha256,
        int recordCount,
        List<MediaReference> references) {
    public MediaCatalogSnapshot {
        references = List.copyOf(references);
    }
}
