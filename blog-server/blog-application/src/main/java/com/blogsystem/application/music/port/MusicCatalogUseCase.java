package com.blogsystem.application.music.port;

import com.blogsystem.application.music.service.MusicCatalogApplicationService;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicMediaUpload;

import java.util.List;

public interface MusicCatalogUseCase {
    List<MusicCatalogApplicationService.TrackView> catalog();

    MusicCatalogApplicationService.TrackView createMetadata(
            String id,
            String title,
            String artist,
            String sourceId,
            boolean enabled,
            int sortOrder);

    MusicCatalogApplicationService.TrackView updateMetadata(
            String id,
            String title,
            String artist,
            String sourceId,
            boolean enabled,
            int sortOrder,
            Long expectedVersion);

    MusicCatalogApplicationService.TrackView upload(String id, MusicMediaUpload upload);

    MusicCatalogApplicationService.TrackView deleteMedia(String id, MusicMediaKind kind);

    void deleteTrack(String id);
}
