package com.blogsystem.infrastructure.adapter.repository.surprise;

import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoMetadataRepository;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMetadataMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/** Explicitly constructed by the import workflow; does not replace the JSON adapter. */
public final class MybatisSurpriseVideoMetadataRepository
        implements SurpriseVideoMetadataRepository {
    private final SurpriseVideoMetadataMapper mapper;

    public MybatisSurpriseVideoMetadataRepository(SurpriseVideoMetadataMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public boolean insertIfAbsent(PersistedSurpriseVideo video, Instant now) {
        Objects.requireNonNull(video, "video");
        Objects.requireNonNull(now, "now");
        return mapper.insertIfAbsent(video, OffsetDateTime.ofInstant(now, ZoneOffset.UTC)) == 1;
    }

    @Override
    public Optional<PersistedSurpriseVideo> findById(String id) {
        Objects.requireNonNull(id, "id");
        var record = mapper.selectById(id);
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(
                new PersistedSurpriseVideo(
                        record.id(),
                        record.title(),
                        record.videoAssetId(),
                        record.enabled(),
                        record.sortOrder()));
    }
}
