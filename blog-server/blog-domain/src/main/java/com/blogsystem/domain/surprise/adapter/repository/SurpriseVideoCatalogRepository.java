package com.blogsystem.domain.surprise.adapter.repository;

import com.blogsystem.domain.surprise.model.SurpriseVideoMetadata;
import com.blogsystem.domain.surprise.model.SurpriseVideoSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SurpriseVideoCatalogRepository {
    List<SurpriseVideoSnapshot> findAdminList();

    Optional<SurpriseVideoSnapshot> findSnapshotById(String id);

    List<SurpriseVideoSnapshot> findPublicCandidates();

    /** Execute CAS and returned snapshot read within the caller's short transaction. */
    SurpriseVideoSnapshot updateMetadata(
            String id, SurpriseVideoMetadata metadata, long expectedVersion, Instant now);

    default SurpriseVideoSnapshot insertNewVideo(
            com.blogsystem.domain.surprise.model.PersistedSurpriseVideo video, Instant now) {
        throw new com.blogsystem.domain.surprise.model.SurpriseCatalogException(
                com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason
                        .OPERATION_NOT_READY);
    }
}
