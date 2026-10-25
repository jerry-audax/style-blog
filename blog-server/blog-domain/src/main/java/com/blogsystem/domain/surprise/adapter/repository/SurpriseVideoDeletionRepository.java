package com.blogsystem.domain.surprise.adapter.repository;

import com.blogsystem.domain.surprise.model.SurpriseVideoSnapshot;

import java.time.Instant;
import java.util.Optional;

public interface SurpriseVideoDeletionRepository {
    Optional<SurpriseVideoSnapshot> findSnapshotById(String id);

    void softDelete(String id, long expectedVersion, Instant now);
}
