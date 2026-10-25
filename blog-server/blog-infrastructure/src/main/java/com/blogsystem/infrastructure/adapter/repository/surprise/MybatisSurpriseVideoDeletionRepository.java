package com.blogsystem.infrastructure.adapter.repository.surprise;

import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoDeletionRepository;
import com.blogsystem.domain.surprise.model.SurpriseVideoSnapshot;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

public final class MybatisSurpriseVideoDeletionRepository
        implements SurpriseVideoDeletionRepository {
    private final SurpriseVideoMapper mapper;

    public MybatisSurpriseVideoDeletionRepository(SurpriseVideoMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SurpriseVideoSnapshot> findSnapshotById(String id) {
        java.util.Objects.requireNonNull(id);
        try {
            return Optional.ofNullable(mapper.selectById(id))
                    .map(com.blogsystem.infrastructure.mybatis.converter.surprise.SurpriseVideoConverter::toSnapshot);
        } catch (RuntimeException failure) {
            throw new com.blogsystem.domain.surprise.model.SurpriseCatalogException(
                    com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.UNAVAILABLE);
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
}
