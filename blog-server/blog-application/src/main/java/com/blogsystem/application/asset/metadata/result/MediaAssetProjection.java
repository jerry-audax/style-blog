package com.blogsystem.application.asset.metadata.result;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

import java.util.UUID;

public record MediaAssetProjection(
        UUID id,
        MediaKind kind,
        MediaAssetStatus status,
        String objectKey,
        String url,
        String mimeType,
        Long size) {}
