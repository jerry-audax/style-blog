package com.blogsystem.trigger.http.asset.request;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadVideoSeed;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadImageSeed;
import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.trigger.http.support.CatalogSortOrderDeserializer;
import com.blogsystem.trigger.http.support.ExpectedVersionDeserializer;
import com.blogsystem.trigger.http.support.MediaUploadSizeDeserializer;
import com.blogsystem.trigger.http.support.MediaUploadUuidDeserializer;
import com.blogsystem.trigger.http.support.OperationVersionDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.UUID;

public final class MediaUploadRequests {
    private MediaUploadRequests() {}

    public record Create(
            @JsonDeserialize(using = MediaUploadUuidDeserializer.class) UUID operationId,
            MediaUploadTargetType targetType,
            String targetId,
            MediaUploadSlot slot,
            @JsonDeserialize(using = ExpectedVersionDeserializer.class) Long expectedVersion,
            String fileName,
            @JsonDeserialize(using = MediaUploadSizeDeserializer.class) Long fileSize,
            String mediaType,
            String sourceSha256,
            String title,
            Boolean enabled,
            @JsonDeserialize(using = CatalogSortOrderDeserializer.class) Integer sortOrder,
            ImagePurpose imagePurpose) {
        public MediaUploadRequest domain() {
            if (operationId == null
                    || targetType == null
                    || slot == null
                    || fileName == null
                    || fileSize == null
                    || mediaType == null
                    || sourceSha256 == null) throw invalid();
            MediaUploadVideoSeed seed = null;
            MediaUploadImageSeed image = null;
            if (targetType == MediaUploadTargetType.MUSIC_TRACK) {
                if (title != null || enabled != null || sortOrder != null || imagePurpose != null) throw invalid();
                requireVersion(expectedVersion);
            } else if (targetType == MediaUploadTargetType.SURPRISE_CREATE) {
                if (title == null || enabled == null || sortOrder == null || imagePurpose != null) throw invalid();
                seed = new MediaUploadVideoSeed(title, enabled, sortOrder);
            } else if (targetType == MediaUploadTargetType.IMAGE_CREATE) {
                if (title != null || enabled != null || sortOrder != null || targetId != null
                        || expectedVersion != null || imagePurpose == null) throw invalid();
                image = new MediaUploadImageSeed(imagePurpose);
            } else {
                throw invalid();
            }
            return new MediaUploadRequest(
                    operationId,
                    targetType,
                    targetId,
                    slot,
                    expectedVersion,
                    new MediaUploadFile(fileName, mediaType, fileSize, sourceSha256),
                    seed,
                    image);
        }
    }

    public record Version(
            @JsonDeserialize(using = OperationVersionDeserializer.class) String operationVersion) {
        public long value() {
            return version(operationVersion);
        }
    }

    public record Bind(
            @JsonDeserialize(using = MediaUploadUuidDeserializer.class) UUID newOperationId,
            @JsonDeserialize(using = ExpectedVersionDeserializer.class) Long expectedVersion,
            @JsonDeserialize(using = OperationVersionDeserializer.class) String operationVersion) {
        public void validate() {
            if (newOperationId == null) throw invalid();
            requireVersion(expectedVersion);
            version(operationVersion);
        }
    }

    public static long version(String value) {
        if (value == null)
            throw new MediaUploadException(MediaUploadException.Reason.MISSING_VERSION);
        return OperationVersionDeserializer.parse(value);
    }

    private static void requireVersion(Long value) {
        if (value == null)
            throw new MediaUploadException(MediaUploadException.Reason.MISSING_VERSION);
    }

    private static MediaUploadException invalid() {
        return new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
    }
}
