package com.blogsystem.infrastructure.adapter.repository.music;

import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicTrackMetadataRepository;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMetadataMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicitly constructed by the import CLI; it does not replace the live JSON catalogue. */
public final class MybatisMusicTrackMetadataRepository implements MusicTrackMetadataRepository {
    private final MusicTrackMetadataMapper mapper;

    public MybatisMusicTrackMetadataRepository(MusicTrackMetadataMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "Music metadata mapper is required");
    }

    @Override
    public boolean insertIfAbsent(PersistedMusicTrack track, Instant now) {
        Objects.requireNonNull(track, "Music metadata is required");
        Objects.requireNonNull(now, "Import time is required");
        return mapper.insertIfAbsent(
                        track, now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                == 1;
    }

    @Override
    public Optional<PersistedMusicTrack> findById(String id) {
        Objects.requireNonNull(id, "Music track ID is required");
        return Optional.ofNullable(mapper.selectById(id))
                .map(MybatisMusicTrackMetadataRepository::toDomain);
    }

    private static PersistedMusicTrack toDomain(Map<String, Object> row) {
        return new PersistedMusicTrack(
                (String) row.get("id"),
                (String) row.get("title"),
                (String) row.get("artist"),
                (String) row.get("source_id"),
                toUuid(row.get("audio_asset_id")),
                toUuid(row.get("cover_asset_id")),
                toUuid(row.get("lyrics_asset_id")),
                (String) row.get("external_cover_url"),
                (Boolean) row.get("enabled"),
                ((Number) row.get("sort_order")).intValue());
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
