package com.blogsystem.infrastructure.adapter.repository.music;

import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.CONFLICT;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.DUPLICATE;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.NOT_FOUND;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.UNAVAILABLE;

import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicTrackMetadata;
import com.blogsystem.domain.music.model.MusicTrackSnapshot;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.infrastructure.mybatis.converter.music.MusicTrackConverter;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.record.music.MusicTrackRecord;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runtime persistence, separate from the additive import adapter. */
public final class MybatisMusicTrackRepository implements MusicTrackRepository {
    private final MusicTrackMapper mapper;
    private final MediaReferenceWriteRepository references;

    public MybatisMusicTrackRepository(MusicTrackMapper mapper,
            MediaReferenceWriteRepository references) {
        this.mapper = Objects.requireNonNull(mapper);
        this.references = Objects.requireNonNull(references);
    }

    @Override
    public List<MusicTrackSnapshot> findAdminList() {
        try {
            return mapper.selectAdminList().stream().map(MusicTrackConverter::toSnapshot).toList();
        } catch (RuntimeException failure) {
            throw new MusicCatalogException(UNAVAILABLE);
        }
    }

    @Override
    public Optional<MusicTrackSnapshot> findSnapshotById(String id) {
        Objects.requireNonNull(id);
        try {
            return Optional.ofNullable(mapper.selectById(id)).map(MusicTrackConverter::toSnapshot);
        } catch (RuntimeException failure) {
            throw new MusicCatalogException(UNAVAILABLE);
        }
    }

    @Override
    public List<MusicTrackSnapshot> findPublicCandidates(int limit) {
        if (limit < 1 || limit > 2001)
            throw new IllegalArgumentException("Invalid public music limit");
        try {
            return mapper.selectPublicCandidates(limit).stream()
                    .map(MusicTrackConverter::toSnapshot)
                    .toList();
        } catch (RuntimeException failure) {
            throw new MusicCatalogException(UNAVAILABLE);
        }
    }

    @Override
    public MusicTrackSnapshot insertNewMusicTrack(PersistedMusicTrack track, Instant now) {
        Objects.requireNonNull(track);
        Objects.requireNonNull(now);
        if (track.audioAssetId() != null
                || track.coverAssetId() != null
                || track.lyricsAssetId() != null
                || track.externalCoverUrl() != null)
            throw new IllegalArgumentException("New runtime music may not bind resources");
        try {
            if (mapper.insert(track, now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                    != 1) throw new MusicCatalogException(UNAVAILABLE);
            return Optional.ofNullable(mapper.selectById(track.id()))
                    .map(MusicTrackConverter::toSnapshot)
                    .orElseThrow(() -> new MusicCatalogException(UNAVAILABLE));
        } catch (MusicCatalogException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new MusicCatalogException(isDuplicate(failure) ? DUPLICATE : UNAVAILABLE);
        }
    }

    @Override
    public MusicTrackSnapshot updateMetadata(
            String id, MusicTrackMetadata metadata, long expectedVersion, Instant now) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(now);
        if (expectedVersion < 0) throw new IllegalArgumentException("Invalid expected version");
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive())
                throw new MusicCatalogException(UNAVAILABLE);
            var previous = mapper.selectById(id);
            if (previous == null) throw new MusicCatalogException(NOT_FOUND);
            if (previous.version() != expectedVersion || expectedVersion == Long.MAX_VALUE)
                throw new MusicCatalogException(CONFLICT);
            var ids = new HashSet<UUID>();
            if (previous.audioAssetId() != null) ids.add(previous.audioAssetId());
            if (previous.coverAssetId() != null) ids.add(previous.coverAssetId());
            if (previous.lyricsAssetId() != null) ids.add(previous.lyricsAssetId());
            var links = previous.externalCoverUrl() == null || previous.externalCoverUrl().isBlank()
                    ? List.<String>of() : List.of(previous.externalCoverUrl());
            var intent = new MediaReferenceWriteIntent(links, List.of(), ids);
            references.lockForWrite(intent, intent);
            var locked = mapper.selectByIdForUpdate(id);
            if (locked == null || locked.version() != previous.version()
                    || !Objects.equals(locked.audioAssetId(), previous.audioAssetId())
                    || !Objects.equals(locked.coverAssetId(), previous.coverAssetId())
                    || !Objects.equals(locked.lyricsAssetId(), previous.lyricsAssetId())
                    || !Objects.equals(locked.externalCoverUrl(), previous.externalCoverUrl()))
                throw new MusicCatalogException(CONFLICT);
            int changed =
                    mapper.updateMetadata(
                            id,
                            metadata,
                            expectedVersion,
                            now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC));
            MusicTrackRecord row = mapper.selectById(id);
            if (changed == 0) throw new MusicCatalogException(row == null ? NOT_FOUND : CONFLICT);
            if (changed != 1 || row == null) throw new MusicCatalogException(UNAVAILABLE);
            return MusicTrackConverter.toSnapshot(row);
        } catch (MediaReferenceWriteException failure) {
            throw new MusicCatalogException(failure.reason() == MediaReferenceWriteException.Reason.CONFLICT
                    ? CONFLICT : UNAVAILABLE);
        } catch (MusicCatalogException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new MusicCatalogException(UNAVAILABLE);
        }
    }

    private static boolean isDuplicate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        return false;
    }

    @Override
    public MusicTrackSnapshot bindMedia(
            String id,
            MusicMediaKind kind,
            java.util.UUID newAssetId,
            long expectedVersion,
            java.util.UUID expectedOldAssetId,
            Instant now) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(newAssetId);
        Objects.requireNonNull(now);
        if (expectedVersion < 0) throw new IllegalArgumentException("Invalid binding version");
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive())
                throw new MusicCatalogException(UNAVAILABLE);
            var previous = mapper.selectById(id);
            if (previous == null) throw new MusicCatalogException(NOT_FOUND);
            UUID old = switch (kind) {
                case AUDIO -> previous.audioAssetId();
                case COVER -> previous.coverAssetId();
                case LYRICS -> previous.lyricsAssetId();
            };
            if (previous.version() != expectedVersion || expectedVersion == Long.MAX_VALUE
                    || !Objects.equals(old, expectedOldAssetId))
                throw new MusicCatalogException(CONFLICT);
            var before = bindingReferences(previous.audioAssetId(), previous.coverAssetId(),
                    previous.lyricsAssetId(), previous.externalCoverUrl());
            var after = bindingReferences(kind == MusicMediaKind.AUDIO ? newAssetId : previous.audioAssetId(),
                    kind == MusicMediaKind.COVER ? newAssetId : previous.coverAssetId(),
                    kind == MusicMediaKind.LYRICS ? newAssetId : previous.lyricsAssetId(), previous.externalCoverUrl());
            // 先锁新旧资源全集，再锁业务行；等待期间引用发生变化时拒绝，不能追加无序资源锁。
            references.lockForWrite(before, after);
            var locked = mapper.selectByIdForUpdate(id);
            if (locked == null || locked.version() != previous.version()
                    || !Objects.equals(locked.audioAssetId(), previous.audioAssetId())
                    || !Objects.equals(locked.coverAssetId(), previous.coverAssetId())
                    || !Objects.equals(locked.lyricsAssetId(), previous.lyricsAssetId())
                    || !Objects.equals(locked.externalCoverUrl(), previous.externalCoverUrl()))
                throw new MusicCatalogException(CONFLICT);
            var time = now.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
            int changed =
                    switch (kind) {
                        case AUDIO ->
                                mapper.bindAudio(
                                        id, newAssetId, expectedVersion, expectedOldAssetId, time);
                        case COVER ->
                                mapper.bindCover(
                                        id, newAssetId, expectedVersion, expectedOldAssetId, time);
                        case LYRICS ->
                                mapper.bindLyrics(
                                        id, newAssetId, expectedVersion, expectedOldAssetId, time);
                    };
            var row = mapper.selectById(id);
            if (changed == 0) throw new MusicCatalogException(row == null ? NOT_FOUND : CONFLICT);
            if (changed != 1 || row == null) throw new MusicCatalogException(UNAVAILABLE);
            return MusicTrackConverter.toSnapshot(row);
        } catch (MediaReferenceWriteException failure) {
            throw new MusicCatalogException(failure.reason() == MediaReferenceWriteException.Reason.CONFLICT
                    ? CONFLICT : UNAVAILABLE);
        } catch (MusicCatalogException safe) {
            throw safe;
        } catch (RuntimeException failure) {
            throw new MusicCatalogException(UNAVAILABLE);
        }
    }

    private static MediaReferenceWriteIntent bindingReferences(UUID audio, UUID cover, UUID lyrics, String externalCover) {
        var ids = new HashSet<UUID>();
        if (audio != null) ids.add(audio);
        if (cover != null) ids.add(cover);
        if (lyrics != null) ids.add(lyrics);
        var links = externalCover == null || externalCover.isBlank() ? List.<String>of() : List.of(externalCover);
        return new MediaReferenceWriteIntent(links, List.of(), ids);
    }
}
