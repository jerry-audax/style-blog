package com.blogsystem.domain.music.adapter.repository;

import com.blogsystem.domain.music.model.MusicTrackMetadata;
import com.blogsystem.domain.music.model.MusicTrackSnapshot;
import com.blogsystem.domain.music.model.PersistedMusicTrack;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MusicTrackRepository {
    List<MusicTrackSnapshot> findAdminList();

    Optional<MusicTrackSnapshot> findSnapshotById(String id);

    List<MusicTrackSnapshot> findPublicCandidates(int limit);

    MusicTrackSnapshot insertNewMusicTrack(PersistedMusicTrack track, Instant now);

    /** Execute CAS and returned snapshot read within the caller's short transaction. */
    MusicTrackSnapshot updateMetadata(
            String id, MusicTrackMetadata metadata, long expectedVersion, Instant now);

    default MusicTrackSnapshot bindMedia(
            String id,
            com.blogsystem.domain.music.model.MusicMediaKind kind,
            java.util.UUID newAssetId,
            long expectedVersion,
            java.util.UUID expectedOldAssetId,
            Instant now) {
        throw new com.blogsystem.domain.music.model.MusicCatalogException(
                com.blogsystem.domain.music.model.MusicCatalogException.Reason.OPERATION_NOT_READY);
    }
}
