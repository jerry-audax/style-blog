package com.blogsystem.trigger.http.asset;

import cn.dev33.satoken.annotation.SaCheckRole;

import com.blogsystem.application.asset.deletion.service.MediaDeletionApplicationService;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/media")
@SaCheckRole("BLOG_OWNER")
@ConditionalOnProperty(name = "media.catalog.storage", havingValue = "postgres")
public final class MediaDeletionController {
    private final MediaDeletionApplicationService service;
    private final IdentityApplicationFacade identity;

    public MediaDeletionController(
            MediaDeletionApplicationService service, IdentityApplicationFacade identity) {
        this.service = service;
        this.identity = identity;
    }

    private long actor() {
        return identity.requireCurrentOwner().id();
    }

    @PostMapping("/deletions/preview")
    public ResponseEntity<ApiResponse<MediaDeletionViews.Preview>> preview(
            @RequestBody MediaDeletionRequests.Preview request) {
        return response(
                200, MediaDeletionViews.preview(service.preview(actor(), request.domain())));
    }

    @PostMapping("/deletions")
    public ResponseEntity<ApiResponse<MediaDeletionViews.Operation>> submit(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody MediaDeletionRequests.Submit request) {
        return response(
                202, MediaDeletionViews.operation(service.submit(actor(), key, request.domain())));
    }

    @GetMapping("/deletions/{id}")
    public ResponseEntity<ApiResponse<MediaDeletionViews.Operation>> get(@PathVariable String id) {
        return response(
                200,
                MediaDeletionViews.operation(service.get(MediaDeletionRequests.uuid(id), actor())));
    }

    @GetMapping("/deletions")
    public ResponseEntity<ApiResponse<MediaDeletionViews.Page>> history(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        long actor = actor();
        if (offset < 0 || limit < 1 || limit > 100) throw MediaDeletionRequests.invalid();
        var page = service.history(actor, offset, limit);
        return response(
                200,
                new MediaDeletionViews.Page(
                        page.records().stream().map(MediaDeletionViews::operation).toList(),
                        page.total()));
    }

    @PostMapping("/assets/{id}/deletion-ownership")
    public ResponseEntity<ApiResponse<MediaDeletionViews.Ownership>> ownership(
            @PathVariable String id, @RequestBody MediaDeletionRequests.Ownership request) {
        return response(
                200,
                MediaDeletionViews.ownership(
                        service.confirmOwnership(
                                actor(),
                                MediaDeletionRequests.uuid(id),
                                request.parsedScope(),
                                request.version(),
                                request.parsedNote())));
    }

    private <T> ResponseEntity<ApiResponse<T>> response(int status, T body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(body));
    }
}
