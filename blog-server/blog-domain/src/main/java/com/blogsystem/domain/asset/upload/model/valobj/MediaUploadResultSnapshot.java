package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;

import java.time.Instant;
import java.util.Objects;

/** Environment-neutral committed business/resource facts. No ORM or provider JSON. */
public record MediaUploadResultSnapshot(
        MediaUploadTargetType targetType,
        String targetId,
        long targetVersion,
        String title,
        String artist,
        String sourceId,
        boolean enabled,
        int sortOrder,
        String externalCoverUrl,
        MediaUploadResourceSnapshot audio,
        MediaUploadResourceSnapshot cover,
        MediaUploadResourceSnapshot lyrics,
        MediaUploadResourceSnapshot video,
        Instant createdAt,
        Instant updatedAt) implements MediaUploadResultFacts {
    public MediaUploadResultSnapshot {
        Objects.requireNonNull(targetType);
        if (targetVersion < 0)
            throw new IllegalArgumentException("Invalid committed target version");
        createdAt = MediaUploadPolicy.precision(createdAt);
        updatedAt = MediaUploadPolicy.precision(updatedAt);
        if (updatedAt.isBefore(createdAt))
            throw new IllegalArgumentException("Invalid committed timestamps");
        requireKind(audio, MediaKind.AUDIO);
        requireKind(cover, MediaKind.IMAGE);
        requireKind(lyrics, MediaKind.LYRICS);
        requireKind(video, MediaKind.VIDEO);
        if (targetType == MediaUploadTargetType.MUSIC_TRACK) {
            if (video != null)
                throw new IllegalArgumentException("Music result cannot contain video");
            new PersistedMusicTrack(
                    targetId,
                    title,
                    artist,
                    sourceId,
                    audio == null ? null : audio.assetId(),
                    cover == null ? null : cover.assetId(),
                    lyrics == null ? null : lyrics.assetId(),
                    externalCoverUrl,
                    enabled,
                    sortOrder);
        } else if (targetType == MediaUploadTargetType.SURPRISE_CREATE) {
            if (audio != null
                    || cover != null
                    || lyrics != null
                    || artist != null
                    || sourceId != null
                    || externalCoverUrl != null
                    || video == null)
                throw new IllegalArgumentException("Invalid video result fields");
            new PersistedSurpriseVideo(targetId, title, video.assetId(), enabled, sortOrder);
        } else throw new IllegalArgumentException("Image facts require their own result variant");
    }

    private static void requireKind(MediaUploadResourceSnapshot value, MediaKind kind) {
        if (value != null && value.kind() != kind)
            throw new IllegalArgumentException("Snapshot resource kind mismatch");
    }
}
