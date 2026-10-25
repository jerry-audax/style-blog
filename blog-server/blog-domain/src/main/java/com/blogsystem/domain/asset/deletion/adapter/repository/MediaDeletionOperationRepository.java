package com.blogsystem.domain.asset.deletion.adapter.repository;

import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaDeletionOperationRepository {
    /** Root and items use the caller's transaction, never an independent commit. */
    MediaDeletionOperation insert(MediaDeletionOperation operation);

    Optional<MediaDeletionOperation> findByActorAndKey(long actorId, String key);

    Optional<MediaDeletionOperation> findById(UUID id, long actorId);

    Page findPage(long actorId, int offset, int limit);

    record Page(List<MediaDeletionOperation> records, long total) {
        public Page {
            records = List.copyOf(records);
            if (total < 0) throw new IllegalArgumentException();
        }
    }
}
