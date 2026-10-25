package com.blogsystem.application.asset.image.service;

import com.blogsystem.application.asset.upload.port.UploadBindingApplicationPort;
import com.blogsystem.application.asset.upload.port.UploadBindingPreparation;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.ImageUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Registering an image never changes an article, profile, or other business binding. */
public final class ImageUploadBindingApplicationService implements UploadBindingApplicationPort {
    private final MediaAssetRepository assets;
    private final MediaReferenceWriteRepository references;

    public ImageUploadBindingApplicationService(
            MediaAssetRepository assets, MediaReferenceWriteRepository references) {
        this.assets = Objects.requireNonNull(assets);
        this.references = Objects.requireNonNull(references);
    }

    @Override public MediaUploadTargetType targetType() { return MediaUploadTargetType.IMAGE_CREATE; }

    @Override public MediaUploadTarget prepareTarget(MediaUploadRequest request, UUID candidateAssetId) {
        if (request.targetType() != targetType() || request.image() == null || candidateAssetId == null)
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        return new MediaUploadTarget(targetType(), candidateAssetId.toString(), request.slot(), null, null, null, request.image());
    }

    @Override public UploadBindingPreparation prepareCommit(MediaUploadOperation.Intent intent) {
        if (intent.target().type() != targetType())
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        return (resource, now) -> {
            if (resource == null) throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
            try {
                references.lockForWrite(
                        MediaReferenceWriteIntent.empty(),
                        new MediaReferenceWriteIntent(
                                List.of(), List.of(), Set.of(resource.assetId())));
            } catch (MediaReferenceWriteException failure) {
                throw new MediaUploadException(
                        failure.reason() == MediaReferenceWriteException.Reason.CONFLICT
                                ? MediaUploadException.Reason.CONFLICT
                                : MediaUploadException.Reason.UNAVAILABLE,
                        failure.reason() == MediaReferenceWriteException.Reason.CONFLICT
                                ? MediaUploadErrorCode.TARGET_CHANGED
                                : MediaUploadErrorCode.DATABASE_UNAVAILABLE);
            }
            var actual = assets.findById(resource.assetId()).orElseThrow(
                    () -> new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST));
            MediaUploadPolicy.validateBindingResource(intent, resource, actual);
            return new ImageUploadResultSnapshot(resource, intent.target().image().purpose(), actual.createdAt(), actual.updatedAt());
        };
    }
}
