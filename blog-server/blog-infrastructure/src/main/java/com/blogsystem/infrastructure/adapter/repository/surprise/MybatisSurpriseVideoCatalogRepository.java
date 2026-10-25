package com.blogsystem.infrastructure.adapter.repository.surprise;

import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.CONFLICT;
import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.INVALID_REQUEST;
import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.NOT_FOUND;
import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.UNAVAILABLE;

import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;
import com.blogsystem.domain.surprise.model.SurpriseCatalogException;
import com.blogsystem.domain.surprise.model.SurpriseVideoMetadata;
import com.blogsystem.domain.surprise.model.SurpriseVideoSnapshot;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoCatalogRepository;
import com.blogsystem.infrastructure.mybatis.converter.surprise.SurpriseVideoConverter;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import com.blogsystem.infrastructure.mybatis.record.surprise.SurpriseVideoRecord;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class MybatisSurpriseVideoCatalogRepository implements SurpriseVideoCatalogRepository {
    private final SurpriseVideoMapper mapper;
    private final MediaReferenceWriteRepository references;

    public MybatisSurpriseVideoCatalogRepository(SurpriseVideoMapper mapper,
            MediaReferenceWriteRepository references) {
        this.mapper = Objects.requireNonNull(mapper);
        this.references = Objects.requireNonNull(references);
    }

    @Override
    public List<SurpriseVideoSnapshot> findAdminList() {
        try {
            return mapper.selectAdminList().stream()
                    .map(SurpriseVideoConverter::toSnapshot)
                    .toList();
        } catch (RuntimeException failure) {
            throw new SurpriseCatalogException(UNAVAILABLE);
        }
    }

    @Override
    public Optional<SurpriseVideoSnapshot> findSnapshotById(String id) {
        Objects.requireNonNull(id);
        try {
            return Optional.ofNullable(mapper.selectById(id))
                    .map(SurpriseVideoConverter::toSnapshot);
        } catch (RuntimeException failure) {
            throw new SurpriseCatalogException(UNAVAILABLE);
        }
    }

    @Override
    public List<SurpriseVideoSnapshot> findPublicCandidates() {
        try {
            return mapper.selectPublicCandidates().stream()
                    .map(SurpriseVideoConverter::toSnapshot)
                    .toList();
        } catch (RuntimeException failure) {
            throw new SurpriseCatalogException(UNAVAILABLE);
        }
    }

    @Override
    public SurpriseVideoSnapshot updateMetadata(
            String id, SurpriseVideoMetadata metadata, long expectedVersion, Instant now) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(now);
        if (expectedVersion < 0) throw new IllegalArgumentException("Invalid expected version");
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive())
                throw new SurpriseCatalogException(UNAVAILABLE);
            var previous = mapper.selectById(id);
            if (previous == null) throw new SurpriseCatalogException(NOT_FOUND);
            if (previous.version() != expectedVersion || expectedVersion == Long.MAX_VALUE)
                throw new SurpriseCatalogException(CONFLICT);
            var intent = new MediaReferenceWriteIntent(
                    List.of(), List.of(), Set.of(previous.videoAssetId()));
            references.lockForWrite(intent, intent);
            var locked = mapper.selectByIdForUpdate(id);
            if (locked == null || locked.version() != previous.version()
                    || !Objects.equals(locked.videoAssetId(), previous.videoAssetId()))
                throw new SurpriseCatalogException(CONFLICT);
            int changed =
                    mapper.updateMetadata(
                            id,
                            metadata,
                            expectedVersion,
                            now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC));
            SurpriseVideoRecord row = mapper.selectById(id);
            if (changed == 0)
                throw new SurpriseCatalogException(row == null ? NOT_FOUND : CONFLICT);
            if (changed != 1 || row == null) throw new SurpriseCatalogException(UNAVAILABLE);
            return SurpriseVideoConverter.toSnapshot(row);
        } catch (MediaReferenceWriteException failure) {
            throw new SurpriseCatalogException(failure.reason() == MediaReferenceWriteException.Reason.CONFLICT
                    ? CONFLICT : UNAVAILABLE);
        } catch (SurpriseCatalogException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SurpriseCatalogException(UNAVAILABLE);
        }
    }

    @Override
    public SurpriseVideoSnapshot insertNewVideo(PersistedSurpriseVideo video, Instant now) {
        Objects.requireNonNull(video);
        Objects.requireNonNull(now);
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive())
                throw new SurpriseCatalogException(UNAVAILABLE);
            references.lockForWrite(MediaReferenceWriteIntent.empty(),
                    new MediaReferenceWriteIntent(List.of(), List.of(), Set.of(video.videoAssetId())));
            if (mapper.insertVideo(
                            video, now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    != 1) throw new SurpriseCatalogException(INVALID_REQUEST);
            return Optional.ofNullable(mapper.selectById(video.id()))
                    .map(SurpriseVideoConverter::toSnapshot)
                    .orElseThrow(() -> new SurpriseCatalogException(UNAVAILABLE));
        } catch (MediaReferenceWriteException failure) {
            throw new SurpriseCatalogException(failure.reason() == MediaReferenceWriteException.Reason.CONFLICT
                    ? CONFLICT : UNAVAILABLE);
        } catch (SurpriseCatalogException safe) {
            throw safe;
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState()))
                    throw new SurpriseCatalogException(CONFLICT);
            throw new SurpriseCatalogException(UNAVAILABLE);
        }
    }
}
