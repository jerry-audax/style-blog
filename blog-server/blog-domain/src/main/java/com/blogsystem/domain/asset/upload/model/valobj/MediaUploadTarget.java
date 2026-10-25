package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import java.util.Objects;
import java.util.UUID;

/** Frozen business identity/version/binding, not the mutable current catalogue. */
public record MediaUploadTarget(
        MediaUploadTargetType type,
        String id,
        MediaUploadSlot slot,
        Long expectedVersion,
        UUID expectedOldAssetId,
        MediaUploadVideoSeed video,
        MediaUploadImageSeed image) {
    public MediaUploadTarget(MediaUploadTargetType type, String id, MediaUploadSlot slot,
            Long expectedVersion, UUID expectedOldAssetId, MediaUploadVideoSeed video) {
        this(type, id, slot, expectedVersion, expectedOldAssetId, video, null);
    }
    public MediaUploadTarget {
        Objects.requireNonNull(type);
        Objects.requireNonNull(slot);
        id = MediaUploadPolicy.text(id, 64, false);
        if (type == MediaUploadTargetType.MUSIC_TRACK) {
            if ((slot != MediaUploadSlot.AUDIO && slot != MediaUploadSlot.COVER && slot != MediaUploadSlot.LYRICS)
                    || expectedVersion == null
                    || expectedVersion < 0
                    || video != null || image != null)
                throw new IllegalArgumentException("Invalid frozen music target");
        } else if (type == MediaUploadTargetType.IMAGE_CREATE) {
            if (!UUID.fromString(id).toString().equals(id) || image == null || slot != image.slot()
                    || expectedVersion != null || expectedOldAssetId != null || video != null)
                throw new IllegalArgumentException("Invalid frozen image target");
        } else if (slot != MediaUploadSlot.VIDEO
                || expectedVersion != null
                || expectedOldAssetId != null
                || video == null || image != null) {
            throw new IllegalArgumentException("Invalid frozen video target");
        }
    }
}
