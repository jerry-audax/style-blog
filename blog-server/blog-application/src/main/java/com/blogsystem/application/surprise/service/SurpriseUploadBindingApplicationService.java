package com.blogsystem.application.surprise.service;

import com.blogsystem.application.asset.upload.port.UploadBindingApplicationPort;
import com.blogsystem.application.asset.upload.port.UploadBindingPreparation;
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
import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.domain.surprise.model.SurpriseCatalogException;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoCatalogRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Allocation occurs once in prepareTarget; retries reuse the frozen business ID. */
public final class SurpriseUploadBindingApplicationService implements UploadBindingApplicationPort {
    private final SurpriseVideoCatalogRepository videos;
    private final MediaAssetRepository assets;
    private final Supplier<UUID> ids;

    public SurpriseUploadBindingApplicationService(
            SurpriseVideoCatalogRepository videos,
            MediaAssetRepository assets,
            Supplier<UUID> ids) {
        this.videos = Objects.requireNonNull(videos);
        this.assets = Objects.requireNonNull(assets);
        this.ids = Objects.requireNonNull(ids);
    }

    @Override
    public MediaUploadTargetType targetType() {
        return MediaUploadTargetType.SURPRISE_CREATE;
    }

    @Override
    public MediaUploadTarget prepareTarget(MediaUploadRequest request, UUID candidateAssetId) {
        if (request.targetType() != targetType() || request.video() == null)
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        return new MediaUploadTarget(
                targetType(),
                ids.get().toString(),
                MediaUploadSlot.VIDEO,
                null,
                null,
                request.video());
    }

    @Override
    public UploadBindingPreparation prepareCommit(MediaUploadOperation.Intent intent) {
        if (intent.target().type() != targetType())
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        return (resource, now) -> {
            try {
                if (resource == null)
                    throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
                var actual =
                        assets.findById(resource.assetId())
                                .orElseThrow(
                                        () ->
                                                new MediaUploadException(
                                                        MediaUploadException.Reason
                                                                .INVALID_REQUEST));
                MediaUploadPolicy.validateBindingResource(intent, resource, actual);
                var seed = intent.target().video();
                var saved =
                        videos.insertNewVideo(
                                new PersistedSurpriseVideo(
                                        intent.target().id(),
                                        seed.title(),
                                        resource.assetId(),
                                        seed.enabled(),
                                        seed.sortOrder()),
                                now);
                var facts =
                        assets.findById(saved.video().videoAssetId())
                                .orElseThrow(
                                        () ->
                                                new MediaUploadException(
                                                        MediaUploadException.Reason.UNAVAILABLE));
                var committed =
                        new MediaUploadResourceSnapshot(
                                facts.id(),
                                facts.provider(),
                                facts.objectKey(),
                                facts.kind(),
                                facts.status(),
                                facts.version(),
                                facts.metadata());
                return new MediaUploadResultSnapshot(
                        targetType(),
                        saved.video().id(),
                        saved.version(),
                        saved.video().title(),
                        null,
                        null,
                        saved.video().enabled(),
                        saved.video().sortOrder(),
                        null,
                        null,
                        null,
                        null,
                        committed,
                        saved.createdAt(),
                        saved.updatedAt());
            } catch (MediaUploadException safe) {
                throw safe;
            } catch (SurpriseCatalogException failure) {
                throw new MediaUploadException(
                        switch (failure.reason()) {
                            case NOT_FOUND -> MediaUploadException.Reason.NOT_FOUND;
                            case CONFLICT -> MediaUploadException.Reason.CONFLICT;
                            case MISSING_VERSION -> MediaUploadException.Reason.MISSING_VERSION;
                            case INVALID_REQUEST -> MediaUploadException.Reason.INVALID_REQUEST;
                            case UNAVAILABLE, OPERATION_NOT_READY ->
                                    MediaUploadException.Reason.UNAVAILABLE;
                        });
            } catch (RuntimeException failure) {
                throw new MediaUploadException(MediaUploadException.Reason.UNAVAILABLE);
            }
        };
    }
}
