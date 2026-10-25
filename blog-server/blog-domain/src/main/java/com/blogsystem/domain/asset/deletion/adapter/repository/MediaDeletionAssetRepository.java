package com.blogsystem.domain.asset.deletion.adapter.repository;

import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MediaDeletionAssetRepository {
    Optional<MediaDeletionAssetSnapshot> findById(UUID id);

    /** Lock assets in UUID order inside the caller's short transaction. */
    List<MediaDeletionAssetSnapshot> lockByIds(Set<UUID> ids);

    MediaDeletionAssetSnapshot confirmOwnership(
            UUID id, MediaDeletionOwnership ownership, long expectedVersion, Instant now);
}
