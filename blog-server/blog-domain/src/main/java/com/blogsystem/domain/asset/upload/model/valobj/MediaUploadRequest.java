package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import java.util.Objects;
import java.util.UUID;

public record MediaUploadRequest(
        UUID operationId,
        MediaUploadTargetType targetType,
        String targetId,
        MediaUploadSlot slot,
        Long expectedVersion,
        MediaUploadFile file,
        MediaUploadVideoSeed video,
        MediaUploadImageSeed image) {
    public MediaUploadRequest(UUID operationId, MediaUploadTargetType targetType, String targetId,
            MediaUploadSlot slot, Long expectedVersion, MediaUploadFile file, MediaUploadVideoSeed video) {
        this(operationId, targetType, targetId, slot, expectedVersion, file, video, null);
    }
    public MediaUploadRequest {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(targetType);
        Objects.requireNonNull(slot);
        Objects.requireNonNull(file);
        if (expectedVersion != null && expectedVersion < 0)
            throw new IllegalArgumentException("Invalid target version");
        if (targetType == MediaUploadTargetType.MUSIC_TRACK) {
            targetId = MediaUploadPolicy.text(targetId, 64, false);
            if ((slot != MediaUploadSlot.AUDIO && slot != MediaUploadSlot.COVER && slot != MediaUploadSlot.LYRICS)
                    || video != null || image != null)
                throw new IllegalArgumentException("Invalid music upload target");
        } else if (targetType == MediaUploadTargetType.IMAGE_CREATE) {
            if (targetId != null || expectedVersion != null || video != null
                    || image == null || slot != image.slot())
                throw new IllegalArgumentException("Invalid image creation request");
        } else if (targetId != null
                || slot != MediaUploadSlot.VIDEO
                || expectedVersion != null
                || video == null || image != null) {
            throw new IllegalArgumentException("Invalid video creation request");
        }
    }
}
