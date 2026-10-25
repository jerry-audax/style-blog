package com.blogsystem.domain.music.adapter.repository;

import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicTrackSnapshot;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Versioned catalogue withdrawal; never deletes a storage object. */
public interface MusicTrackDeletionRepository {
    Optional<MusicTrackSnapshot> findSnapshotById(String id);

    void softDelete(String id, long expectedVersion, Instant now);

    MusicTrackSnapshot unbindMedia(
            String id,
            MusicMediaKind kind,
            UUID expectedAssetId,
            long expectedVersion,
            Instant now);
}
