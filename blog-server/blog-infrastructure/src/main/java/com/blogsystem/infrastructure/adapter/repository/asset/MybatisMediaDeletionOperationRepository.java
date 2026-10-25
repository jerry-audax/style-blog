package com.blogsystem.infrastructure.adapter.repository.asset;

import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionOperationRepository;
import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.Item;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaDeletionConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionOperationMapper;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaDeleteItemRecord;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaDeleteOperationRecord;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MybatisMediaDeletionOperationRepository
        implements MediaDeletionOperationRepository {
    private final MediaDeletionOperationMapper mapper;
    private final MediaDeletionConverter converter;

    public MybatisMediaDeletionOperationRepository(
            MediaDeletionOperationMapper mapper, MediaDeletionConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    @Override
    public MediaDeletionOperation insert(MediaDeletionOperation operation) {
        try {
            mapper.insertOperation(converter.operation(operation));
            for (var item : operation.items())
                mapper.insertItem(converter.item(item, operation.id(), operation.committedAt()));
            return operation;
        } catch (RuntimeException error) {
            throw failure(error);
        }
    }

    @Override
    public Optional<MediaDeletionOperation> findByActorAndKey(long actorId, String key) {
        try {
            return Optional.ofNullable(read(mapper.selectByActorAndKey(actorId, key)));
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    @Override
    public Optional<MediaDeletionOperation> findById(UUID id, long actorId) {
        try {
            return Optional.ofNullable(read(mapper.selectById(id, actorId)));
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    @Override
    public Page findPage(long actorId, int offset, int limit) {
        if (actorId <= 0 || offset < 0 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("无效查询");
        try {
            return new Page(
                    mapper.selectPage(actorId, offset, limit).stream().map(this::read).toList(),
                    mapper.count(actorId));
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    private MediaDeletionOperation read(MediaDeleteOperationRecord row) {
        if (row == null) return null;
        return converter.operation(
                row, mapper.selectItems(row.getId()).stream().map(this::readItem).toList());
    }

    private Item readItem(MediaDeleteItemRecord row) {
        Item linked = null;
        if (row.getLinkedItemId() != null) {
            var canonical = Objects.requireNonNull(mapper.selectItem(row.getLinkedItemId()));
            if (canonical.getLinkedItemId() != null)
                throw new IllegalStateException("Invalid canonical link");
            linked = converter.item(canonical, null);
        }
        return converter.item(row, linked);
    }

    private MediaDeletionException failure(RuntimeException error) {
        for (Throwable e = error; e != null; e = e.getCause())
            if (e instanceof SQLException sql && "23505".equals(sql.getSQLState()))
                return new MediaDeletionException(MediaDeletionException.Reason.CONFLICT);
        return new MediaDeletionException(MediaDeletionException.Reason.UNAVAILABLE);
    }
}
