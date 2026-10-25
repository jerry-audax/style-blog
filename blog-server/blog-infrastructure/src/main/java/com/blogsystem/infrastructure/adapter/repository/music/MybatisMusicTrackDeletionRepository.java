package com.blogsystem.infrastructure.adapter.repository.music;

import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.music.adapter.repository.MusicTrackDeletionRepository;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicTrackSnapshot;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reuses existing projections, adding only versioned withdrawal commands. */
public final class MybatisMusicTrackDeletionRepository implements MusicTrackDeletionRepository {
    private final MusicTrackMapper mapper;

    public MybatisMusicTrackDeletionRepository(MusicTrackMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<MusicTrackSnapshot> findSnapshotById(String id) {
        Objects.requireNonNull(id);
        try {
            return Optional.ofNullable(mapper.selectById(id))
                    .map(com.blogsystem.infrastructure.mybatis.converter.music.MusicTrackConverter::toSnapshot);
        } catch (RuntimeException failure) {
            throw new com.blogsystem.domain.music.model.MusicCatalogException(
                    com.blogsystem.domain.music.model.MusicCatalogException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public void softDelete(String id, long expectedVersion, Instant now) {
        try {
            if (mapper.softDelete(
                            id,
                            expectedVersion,
                            now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    != 1) throw new MediaDeletionException(MediaDeletionException.Reason.CONFLICT);
        } catch (MediaDeletionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MediaDeletionException(MediaDeletionException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public MusicTrackSnapshot unbindMedia(
            String id,
            MusicMediaKind kind,
            UUID expectedAssetId,
            long expectedVersion,
            Instant now) {
        Objects.requireNonNull(kind);
        try {
            if (mapper.unbindMedia(
                            id,
                            kind.name(),
                            expectedAssetId,
                            expectedVersion,
                            now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    != 1) throw new MediaDeletionException(MediaDeletionException.Reason.CONFLICT);
            return findSnapshotById(id)
                    .orElseThrow(
                            () ->
                                    new MediaDeletionException(
                                            MediaDeletionException.Reason.CONFLICT));
        } catch (MediaDeletionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MediaDeletionException(MediaDeletionException.Reason.UNAVAILABLE);
        }
    }
}
