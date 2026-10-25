package com.blogsystem.domain.music.adapter.repository;

import com.blogsystem.domain.music.model.PersistedMusicTrack;

import java.time.Instant;
import java.util.Optional;

/** One-time import port; an existing catalogue row is never overwritten. */
public interface MusicTrackMetadataRepository {
    boolean insertIfAbsent(PersistedMusicTrack track, Instant now);

    Optional<PersistedMusicTrack> findById(String id);
}
