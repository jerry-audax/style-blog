package com.blogsystem.infrastructure.adapter.repository.asset;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetPage;
import com.blogsystem.domain.asset.metadata.model.query.MediaAssetQuery;
import com.blogsystem.domain.asset.metadata.model.valobj.DuplicateMediaAssetException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetReadException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetWriteConflictException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaAssetRecord;

import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "media.catalog.storage",
        havingValue = "postgres")
public class MybatisMediaAssetRepository implements MediaAssetRepository {
    private final MediaAssetMapper mapper;
    private final MediaAssetConverter persistenceMapper;

    public MybatisMediaAssetRepository(
            MediaAssetMapper mapper, MediaAssetConverter persistenceMapper) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
    }

    @Override
    public Optional<MediaAsset> findById(UUID id) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectById(id)));
    }

    @Override
    public List<MediaAsset> findByIds(Set<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        if (ids.size() > 1000) throw new IllegalArgumentException("Asset batch exceeds 1000 IDs");
        try {
            return mapper.selectByIds(Set.copyOf(ids)).stream()
                    .map(persistenceMapper::toDomain)
                    .toList();
        } catch (RuntimeException failure) {
            throw new MediaAssetReadException();
        }
    }

    @Override
    public Optional<MediaAsset> findByLocation(String provider, MediaObjectKey objectKey) {
        return Optional.ofNullable(
                persistenceMapper.toDomain(mapper.selectByLocation(provider, objectKey.value())));
    }

    @Override
    public List<MediaAsset> findByLocations(String provider, Set<MediaObjectKey> objectKeys) {
        if (provider == null || provider.isBlank() || objectKeys == null || objectKeys.size() > 1000
                || objectKeys.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("资源定位批次不合法");
        }
        if (objectKeys.isEmpty()) return List.of();
        try {
            return mapper.selectByLocations(provider, Set.copyOf(objectKeys)).stream()
                    .map(persistenceMapper::toDomain).toList();
        } catch (RuntimeException failure) {
            throw new MediaAssetReadException();
        }
    }

    @Override
    public MediaAsset insert(MediaAsset asset) {
        if (asset.status() != MediaAssetStatus.ACTIVE || asset.version() != 0) {
            throw new IllegalArgumentException("只能插入 ACTIVE 且版本为 0 的新资源");
        }
        try {
            mapper.insert(persistenceMapper.toRecord(asset));
            return asset;
        } catch (DuplicateKeyException failure) {
            throw new DuplicateMediaAssetException("资源 ID 或存储定位已存在", failure);
        } catch (PersistenceException failure) {
            if (isUniqueViolation(failure)) {
                throw new DuplicateMediaAssetException("资源 ID 或存储定位已存在", failure);
            }
            throw failure;
        }
    }

    @Override
    public MediaAsset updateMetadata(MediaAsset candidate, long expectedVersion) {
        if (expectedVersion < 0 || candidate.version() != expectedVersion) {
            throw new MediaAssetWriteConflictException("资源版本快照不匹配");
        }
        MediaAssetRecord record = persistenceMapper.toRecord(candidate);
        MediaAssetRecord updated =
                mapper.updateMetadata(
                        record.getId(),
                        record.getProvider(),
                        record.getObjectKey(),
                        record.getMediaKind(),
                        record.getStatus(),
                        expectedVersion,
                        record.getFileName(),
                        record.getMimeType(),
                        record.getFileSize(),
                        record.getSha256(),
                        record.getWidth(),
                        record.getHeight(),
                        record.getDurationMs(),
                        record.getUpdatedAt());
        if (updated == null) {
            throw new MediaAssetWriteConflictException("资源已被其他写入者修改或状态不匹配");
        }
        return persistenceMapper.toDomain(updated);
    }

    @Override
    public MediaAssetPage findPage(MediaAssetQuery query) {
        String kind = query.kind() == null ? null : query.kind().name();
        String status = query.status() == null ? null : query.status().name();
        List<MediaAsset> records =
                mapper.selectPage(kind, status, query.limit(), query.offset()).stream()
                        .map(persistenceMapper::toDomain)
                        .toList();
        return new MediaAssetPage(
                records, mapper.count(kind, status), query.offset(), query.limit());
    }

    private static boolean isUniqueViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
