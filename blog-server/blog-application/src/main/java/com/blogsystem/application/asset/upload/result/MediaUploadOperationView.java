package com.blogsystem.application.asset.upload.result;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStage;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;

import java.time.Instant;
import java.util.UUID;

public record MediaUploadOperationView(
        UUID operationId,
        String operationVersion,
        MediaUploadStatus status,
        MediaUploadStage stage,
        MediaUploadTargetType targetType,
        String targetId,
        MediaUploadSlot slot,
        long confirmedBytes,
        long totalBytes,
        int confirmedChunks,
        int totalChunks,
        boolean canPause,
        boolean pauseRequested,
        boolean canResumeContent,
        boolean canRecover,
        boolean canBind,
        MediaUploadErrorCode safeErrorCode,
        Instant createdAt,
        Instant updatedAt,
        MediaUploadResultView result,
        UUID resolvedByOperationId) {}
