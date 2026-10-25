package com.blogsystem.trigger.http.asset;

import cn.dev33.satoken.annotation.SaCheckRole;

import com.blogsystem.application.asset.deletion.service.MediaDeletionApplicationService;
import com.blogsystem.application.asset.metadata.result.MediaCatalogCapabilities;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/admin/media")
@SaCheckRole("BLOG_OWNER")
public final class MediaCatalogCapabilitiesController {
    private final MediaCatalogCapabilities capabilities;
    private final Optional<MediaDeletionApplicationService> deletions;

    public MediaCatalogCapabilitiesController(MediaCatalogCapabilities capabilities) {
        this(capabilities, Optional.empty());
    }

    @Autowired
    public MediaCatalogCapabilitiesController(
            MediaCatalogCapabilities capabilities,
            Optional<MediaDeletionApplicationService> deletions) {
        this.capabilities = capabilities;
        this.deletions = deletions;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<ApiResponse<MediaCatalogCapabilities>> capabilities() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.ok(
                                deletions
                                        .map(
                                                service ->
                                                        capabilities.withTrackedDeletion(
                                                                service.submissionEnabled()))
                                        .orElse(capabilities)));
    }
}
