package com.blogsystem.application.surprise.port;

import com.blogsystem.application.surprise.result.SurpriseVideoAdminView;
import com.blogsystem.application.surprise.service.SurpriseVideoApplicationService;
import com.blogsystem.domain.surprise.model.SurpriseVideoUpload;

import java.util.List;

public interface SurpriseVideoUseCase {
    List<SurpriseVideoAdminView> adminCatalog();

    SurpriseVideoApplicationService.VideoView randomPublic();

    SurpriseVideoAdminView updateMetadata(
            String id, String title, Boolean enabled, Integer sortOrder, Long expectedVersion);

    SurpriseVideoApplicationService.VideoView create(
            String title, boolean enabled, int sortOrder, SurpriseVideoUpload upload);

    void delete(String id);
}
