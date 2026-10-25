package com.blogsystem.domain.asset.inventory.repository;

import com.blogsystem.domain.asset.inventory.model.query.MediaCatalogSnapshot;

import java.nio.file.Path;

public interface MediaSnapshotRepository {
    MediaCatalogSnapshot readMusic(Path path);

    MediaCatalogSnapshot readSurprise(Path path);
}
