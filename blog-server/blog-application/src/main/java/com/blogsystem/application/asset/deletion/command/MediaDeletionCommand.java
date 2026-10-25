package com.blogsystem.application.asset.deletion.command;

import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionTarget;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MediaDeletionCommand(
        MediaDeletionTarget target, long expectedVersion, Map<UUID, Long> assetVersions) {
    public MediaDeletionCommand {
        Objects.requireNonNull(target);
        assetVersions = Map.copyOf(assetVersions);
        if (expectedVersion < 0
                || assetVersions.size() > 3
                || assetVersions.values().stream().anyMatch(v -> v < 0))
            throw new IllegalArgumentException("无效删除版本");
    }
}
