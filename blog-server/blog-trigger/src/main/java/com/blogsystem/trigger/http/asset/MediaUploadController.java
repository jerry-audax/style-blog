package com.blogsystem.trigger.http.asset;

import cn.dev33.satoken.annotation.SaCheckRole;

import com.blogsystem.application.asset.upload.assembler.MediaUploadViewProjector;
import com.blogsystem.application.asset.upload.result.MediaUploadOperationPageView;
import com.blogsystem.application.asset.upload.result.MediaUploadOperationView;
import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.trigger.http.asset.request.MediaUploadRequests;
import com.blogsystem.trigger.http.support.ApiResponse;
import com.blogsystem.trigger.http.support.MediaUploadUuidDeserializer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/media/uploads")
@SaCheckRole("BLOG_OWNER")
@ConditionalOnExpression(
        "'${media.catalog.storage:legacy-json}' == 'postgres' &&"
                + " ${media.catalog.uploads.enabled:false}")
public final class MediaUploadController {
    private final MediaUploadApplicationService service;
    private final MediaUploadViewProjector projector;
    private final IdentityApplicationFacade identity;

    public MediaUploadController(
            MediaUploadApplicationService service,
            MediaUploadViewProjector projector,
            IdentityApplicationFacade identity) {
        this.service = service;
        this.projector = projector;
        this.identity = identity;
    }

    private long actor() {
        return identity.requireCurrentOwner().id();
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MediaUploadOperationView>> create(
            @RequestBody MediaUploadRequests.Create request) {
        var submitted = service.create(actor(), request.domain());
        return response(submitted.operation(), submitted.created() ? 201 : 200, true);
    }

    @PutMapping("/{id}/content")
    public ResponseEntity<ApiResponse<MediaUploadOperationView>> content(
            @PathVariable String id, @RequestParam("file") MultipartFile file) {
        return response(
                service.content(
                        actor(),
                        MediaUploadUuidDeserializer.parse(id),
                        new MultipartMediaUploadContent(file)),
                200,
                true);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<ApiResponse<MediaUploadOperationView>> pause(
            @PathVariable String id, @RequestBody MediaUploadRequests.Version version) {
        return response(
                service.pause(actor(), MediaUploadUuidDeserializer.parse(id), version.value()),
                200,
                true);
    }

    @PostMapping("/{id}/recover")
    public ResponseEntity<ApiResponse<MediaUploadOperationView>> recover(
            @PathVariable String id, @RequestBody MediaUploadRequests.Version version) {
        return response(
                service.recover(actor(), MediaUploadUuidDeserializer.parse(id), version.value()),
                200,
                true);
    }

    @PostMapping("/{id}/bind")
    public ResponseEntity<ApiResponse<MediaUploadOperationView>> bind(
            @PathVariable String id, @RequestBody MediaUploadRequests.Bind request) {
        long actor = actor();
        request.validate();
        var submitted =
                service.bind(
                        actor,
                        MediaUploadUuidDeserializer.parse(id),
                        request.newOperationId(),
                        request.expectedVersion(),
                        MediaUploadRequests.version(request.operationVersion()));
        return response(submitted.operation(), 200, true);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MediaUploadOperationView>> get(@PathVariable String id) {
        return response(service.get(actor(), MediaUploadUuidDeserializer.parse(id)), 200, false);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<MediaUploadOperationPageView>> history(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(projector.page(service.history(actor(), offset, limit))));
    }

    private ResponseEntity<ApiResponse<MediaUploadOperationView>> response(
            MediaUploadOperation op, int preferred, boolean mutation) {
        int status =
                switch (op.progress().status()) {
                    case UPLOADING, PAUSED, UNKNOWN, UPLOADED -> 202;
                    case SUCCEEDED -> 200;
                    case CONFLICT -> mutation ? 409 : 200;
                    case FAILED -> mutation ? 422 : 200;
                    default -> preferred;
                };
        var view = projector.view(op);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(
                        status < 400
                                ? ApiResponse.ok(view)
                                : new ApiResponse<>(status, "上传操作需要核对", view));
    }
}
