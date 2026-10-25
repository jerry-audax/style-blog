package com.blogsystem.domain.surprise.adapter.repository;

import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;

import java.time.Instant;
import java.util.Optional;

/** Import metadata without replacing any video that already has this identity. */
public interface SurpriseVideoMetadataRepository {
    boolean insertIfAbsent(PersistedSurpriseVideo video, Instant now);

    Optional<PersistedSurpriseVideo> findById(String id);
}
