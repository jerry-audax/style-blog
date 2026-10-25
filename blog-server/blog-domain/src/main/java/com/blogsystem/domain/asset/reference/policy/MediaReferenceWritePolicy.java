package com.blogsystem.domain.asset.reference.policy;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Only references retained by the new state must remain ACTIVE; old removals may be repaired. */
public final class MediaReferenceWritePolicy {
    public void requireActive(Set<UUID> desiredIds, List<MediaAsset> lockedAssets) {
        if (desiredIds == null || lockedAssets == null) throw conflict();
        Map<UUID, MediaAsset> byId = new HashMap<>();
        for (MediaAsset asset : lockedAssets) {
            if (asset == null || byId.putIfAbsent(asset.id(), asset) != null) throw conflict();
        }
        for (UUID id : desiredIds) {
            MediaAsset asset = byId.get(id);
            if (id == null || asset == null || asset.status() != MediaAssetStatus.ACTIVE) {
                throw conflict();
            }
        }
    }

    private static MediaReferenceWriteException conflict() {
        return new MediaReferenceWriteException(MediaReferenceWriteException.Reason.CONFLICT);
    }
}
