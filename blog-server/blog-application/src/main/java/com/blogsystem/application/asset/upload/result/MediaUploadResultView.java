package com.blogsystem.application.asset.upload.result;

import com.blogsystem.application.music.service.MusicCatalogApplicationService;
import com.blogsystem.application.surprise.result.SurpriseVideoAdminView;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.application.asset.image.result.ImageAssetView;

public record MediaUploadResultView(
        MediaUploadTargetType targetType,
        MusicCatalogApplicationService.TrackView music,
        SurpriseVideoAdminView video,
        ImageAssetView image) {
    public MediaUploadResultView(MediaUploadTargetType targetType,
            MusicCatalogApplicationService.TrackView music, SurpriseVideoAdminView video) {
        this(targetType, music, video, null);
    }
}
