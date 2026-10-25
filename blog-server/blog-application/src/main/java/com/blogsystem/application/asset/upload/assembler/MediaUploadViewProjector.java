package com.blogsystem.application.asset.upload.assembler;

import com.blogsystem.application.asset.upload.result.MediaUploadOperationPageView;
import com.blogsystem.application.asset.upload.result.MediaUploadOperationView;
import com.blogsystem.application.asset.upload.result.MediaUploadResultView;
import com.blogsystem.application.music.service.MusicCatalogApplicationService;
import com.blogsystem.application.surprise.result.SurpriseVideoAdminView;
import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.query.MediaUploadPage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadOperationType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultFacts;
import com.blogsystem.domain.asset.upload.model.valobj.ImageUploadResultSnapshot;
import com.blogsystem.application.asset.image.result.ImageAssetView;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTransport;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import java.net.URI;

/** Pure committed-facts projection: cannot query a catalogue, provider or artwork service. */
public final class MediaUploadViewProjector {
    private final MediaPublicUrlGateway urls;

    public MediaUploadViewProjector(MediaPublicUrlGateway urls) {
        this.urls = urls;
    }

    public MediaUploadOperationView view(MediaUploadOperation operation) {
        var intent = operation.intent();
        var progress = operation.progress();
        var outcome = operation.outcome();
        var status = progress.status();
        boolean uploading = status == MediaUploadStatus.UPLOADING;
        return new MediaUploadOperationView(
                intent.id(),
                Long.toString(operation.version()),
                status,
                progress.stage(),
                intent.target().type(),
                intent.target().id(),
                intent.target().slot(),
                Math.min(
                        intent.file().size(),
                        (long) progress.confirmedChunks() * MediaUploadPolicy.CHUNK_BYTES),
                intent.file().size(),
                progress.confirmedChunks(),
                intent.totalChunks(),
                uploading
                        && intent.transportMode() == MediaUploadTransport.CHUNKED
                        && (progress.stage() == MediaUploadStage.INIT
                                || progress.stage() == MediaUploadStage.CHUNK)
                        && !progress.pauseRequested(),
                progress.pauseRequested(),
                status == MediaUploadStatus.READY || status == MediaUploadStatus.PAUSED,
                status == MediaUploadStatus.UNKNOWN
                        || status == MediaUploadStatus.UPLOADED
                        || uploading,
                status == MediaUploadStatus.CONFLICT
                        && intent.operationType() == MediaUploadOperationType.UPLOAD_AND_BIND
                        && intent.target().type() == MediaUploadTargetType.MUSIC_TRACK
                        && outcome.resolvedByOperationId() == null,
                outcome.safeErrorCode(),
                operation.createdAt(),
                operation.updatedAt(),
                outcome.result() == null ? null : result(outcome.result()),
                outcome.resolvedByOperationId());
    }

    public MediaUploadOperationPageView page(MediaUploadPage page) {
        return new MediaUploadOperationPageView(
                page.records().stream().map(this::view).toList(),
                page.total(),
                page.offset(),
                page.limit());
    }

    public void validate(MediaUploadResultFacts facts) {
        result(facts);
    }

    private MediaUploadResultView result(MediaUploadResultFacts result) {
        if (result instanceof ImageUploadResultSnapshot image)
            return new MediaUploadResultView(image.targetType(), null, null,
                    ImageAssetView.from(image.resource(), image.createdAt(), image.updatedAt(), urls));
        if (!(result instanceof MediaUploadResultSnapshot facts))
            throw new IllegalArgumentException("Unsupported upload projection");
        if (facts.targetType() == MediaUploadTargetType.MUSIC_TRACK) {
            var cover = media(facts.cover(), MediaKind.IMAGE);
            if (cover == null && safeExternal(facts.externalCoverUrl()))
                cover =
                        new MusicCatalogApplicationService.MediaView(
                                "", facts.externalCoverUrl(), null, (Long) null);
            return new MediaUploadResultView(
                    facts.targetType(),
                    new MusicCatalogApplicationService.TrackView(
                            facts.targetId(),
                            facts.title(),
                            facts.artist(),
                            facts.sourceId(),
                            facts.enabled(),
                            facts.sortOrder(),
                            media(facts.audio(), MediaKind.AUDIO),
                            cover,
                            media(facts.lyrics(), MediaKind.LYRICS),
                            facts.targetVersion()),
                    null);
        }
        if (facts.targetType() != MediaUploadTargetType.SURPRISE_CREATE)
            throw new IllegalArgumentException("Unsupported upload result");
        var video = facts.video();
        return new MediaUploadResultView(
                facts.targetType(),
                null,
                new SurpriseVideoAdminView(
                        facts.targetId(),
                        facts.title(),
                        urls.publicUrl(video.provider(), video.objectKey()),
                        video.objectKey().value(),
                        video.metadata().mimeType(),
                        video.metadata().fileSize(),
                        facts.enabled(),
                        facts.sortOrder(),
                        facts.targetVersion()));
    }

    private MusicCatalogApplicationService.MediaView media(
            MediaUploadResourceSnapshot resource, MediaKind kind) {
        if (resource == null
                || resource.kind() != kind
                || resource.status() != MediaAssetStatus.ACTIVE) return null;
        return new MusicCatalogApplicationService.MediaView(
                resource.objectKey().value(),
                urls.publicUrl(resource.provider(), resource.objectKey()),
                resource.metadata().mimeType(),
                resource.metadata().fileSize());
    }

    private static boolean safeExternal(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
