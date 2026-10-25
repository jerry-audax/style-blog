package com.blogsystem.application.music.service;

import com.blogsystem.application.asset.upload.port.UploadBindingApplicationPort;
import com.blogsystem.application.asset.upload.port.UploadBindingPreparation;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Music owns its CAS. No coordinator reference, ORM, network, or independent transaction. */
public final class MusicUploadBindingApplicationService implements UploadBindingApplicationPort {
    private final MusicTrackRepository tracks;
    private final MediaAssetRepository assets;

    public MusicUploadBindingApplicationService(
            MusicTrackRepository tracks, MediaAssetRepository assets) {
        this.tracks = Objects.requireNonNull(tracks);
        this.assets = Objects.requireNonNull(assets);
    }

    @Override
    public MediaUploadTargetType targetType() {
        return MediaUploadTargetType.MUSIC_TRACK;
    }

    @Override
    public MediaUploadTarget prepareTarget(MediaUploadRequest request, UUID candidateAssetId) {
        return guarded(
                () -> {
                    if (request.targetType() != targetType()) invalid();
                    if (request.expectedVersion() == null)
                        throw new MediaUploadException(MediaUploadException.Reason.MISSING_VERSION);
                    var row =
                            tracks.findSnapshotById(request.targetId())
                                    .orElseThrow(
                                            () ->
                                                    new MediaUploadException(
                                                            MediaUploadException.Reason.NOT_FOUND));
                    if (row.version() != request.expectedVersion())
                        throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
                    return new MediaUploadTarget(
                            targetType(),
                            row.track().id(),
                            request.slot(),
                            request.expectedVersion(),
                            slot(row.track(), request.slot()),
                            null);
                });
    }

    @Override
    public UploadBindingPreparation prepareCommit(MediaUploadOperation.Intent intent) {
        if (intent.target().type() != targetType()) invalid();
        return (resource, now) ->
                guarded(
                        () -> {
                            if (resource == null) invalid();
                            var actual =
                                    assets.findById(resource.assetId())
                                            .orElseThrow(
                                                    () ->
                                                            new MediaUploadException(
                                                                    MediaUploadException.Reason
                                                                            .INVALID_REQUEST));
                            MediaUploadPolicy.validateBindingResource(intent, resource, actual);
                            var target = intent.target();
                            var saved =
                                    tracks.bindMedia(
                                            target.id(),
                                            MusicMediaKind.valueOf(target.slot().name()),
                                            resource.assetId(),
                                            target.expectedVersion(),
                                            target.expectedOldAssetId(),
                                            now);
                            var t = saved.track();
                            var ids =
                                    java.util.stream.Stream.of(
                                                    t.audioAssetId(),
                                                    t.coverAssetId(),
                                                    t.lyricsAssetId())
                                            .filter(Objects::nonNull)
                                            .collect(Collectors.toSet());
                            var facts =
                                    assets.findByIds(ids).stream()
                                            .collect(
                                                    Collectors.toMap(
                                                            MediaAsset::id,
                                                            MusicUploadBindingApplicationService
                                                                    ::snapshot));
                            if (!facts.keySet().containsAll(ids))
                                throw new MediaUploadException(
                                        MediaUploadException.Reason.UNAVAILABLE);
                            return new MediaUploadResultSnapshot(
                                    targetType(),
                                    t.id(),
                                    saved.version(),
                                    t.title(),
                                    t.artist(),
                                    t.sourceId(),
                                    t.enabled(),
                                    t.sortOrder(),
                                    t.externalCoverUrl(),
                                    facts.get(t.audioAssetId()),
                                    facts.get(t.coverAssetId()),
                                    facts.get(t.lyricsAssetId()),
                                    null,
                                    saved.createdAt(),
                                    saved.updatedAt());
                        });
    }

    private static UUID slot(PersistedMusicTrack t, MediaUploadSlot slot) {
        return switch (slot) {
            case AUDIO -> t.audioAssetId();
            case COVER -> t.coverAssetId();
            case LYRICS -> t.lyricsAssetId();
            case VIDEO, IMAGE, AVATAR ->
                    throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        };
    }

    private static MediaUploadResourceSnapshot snapshot(MediaAsset a) {
        return new MediaUploadResourceSnapshot(
                a.id(),
                a.provider(),
                a.objectKey(),
                a.kind(),
                a.status(),
                a.version(),
                a.metadata());
    }

    private static void invalid() {
        throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
    }

    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (MediaUploadException safe) {
            throw safe;
        } catch (MusicCatalogException failure) {
            throw new MediaUploadException(
                    switch (failure.reason()) {
                        case NOT_FOUND -> MediaUploadException.Reason.NOT_FOUND;
                        case CONFLICT, DUPLICATE -> MediaUploadException.Reason.CONFLICT;
                        case MISSING_VERSION -> MediaUploadException.Reason.MISSING_VERSION;
                        case INVALID_REQUEST, TOO_LARGE ->
                                MediaUploadException.Reason.INVALID_REQUEST;
                        case UNAVAILABLE, OPERATION_NOT_READY ->
                                MediaUploadException.Reason.UNAVAILABLE;
                    });
        } catch (RuntimeException failure) {
            throw new MediaUploadException(MediaUploadException.Reason.UNAVAILABLE);
        }
    }
}
