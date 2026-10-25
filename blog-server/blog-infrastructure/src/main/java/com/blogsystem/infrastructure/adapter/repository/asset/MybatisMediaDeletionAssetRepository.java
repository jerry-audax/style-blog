package com.blogsystem.infrastructure.adapter.repository.asset;

import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaDeletionConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaDeletionAssetRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MybatisMediaDeletionAssetRepository implements MediaDeletionAssetRepository {
    private final MediaDeletionAssetMapper mapper;
    private final MediaDeletionConverter converter;

    public MybatisMediaDeletionAssetRepository(
            MediaDeletionAssetMapper mapper, MediaDeletionConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    @Override
    public Optional<MediaDeletionAssetSnapshot> findById(UUID id) {
        try {
            return Optional.ofNullable(converter.asset(mapper.selectById(id)));
        } catch (RuntimeException e) {
            throw new MediaDeletionException(MediaDeletionException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public List<MediaDeletionAssetSnapshot> lockByIds(Set<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        if (ids.size() > 1000) throw new IllegalArgumentException("资源批次过大");
        try {
            return mapper.lockByIds(Set.copyOf(ids)).stream().map(converter::asset).toList();
        } catch (RuntimeException e) {
            throw new MediaDeletionException(MediaDeletionException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public MediaDeletionAssetSnapshot confirmOwnership(
            UUID id, MediaDeletionOwnership ownership, long expectedVersion, Instant now) {
        if (expectedVersion < 0) throw new IllegalArgumentException();
        var row = new MediaDeletionAssetRecord();
        row.setId(id);
        row.setDeletionScope(ownership.scope().name());
        row.setExclusiveEnvironment(ownership.exclusiveEnvironment());
        row.setOwnershipConfirmedBy(ownership.confirmedBy());
        row.setOwnershipConfirmedAt(MediaDeletionConverter.at(ownership.confirmedAt()));
        row.setOwnershipNote(ownership.note());
        row.setUpdatedAt(MediaDeletionConverter.at(now));
        MediaDeletionAssetRecord saved;
        try {
            saved = mapper.confirmOwnership(row, expectedVersion);
        } catch (RuntimeException e) {
            throw new MediaDeletionException(MediaDeletionException.Reason.UNAVAILABLE);
        }
        if (saved == null) throw new MediaDeletionException(MediaDeletionException.Reason.CONFLICT);
        return converter.asset(saved);
    }
}
