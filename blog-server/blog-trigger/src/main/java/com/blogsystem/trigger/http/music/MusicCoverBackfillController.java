package com.blogsystem.trigger.http.music;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.blogsystem.application.asset.upload.assembler.MediaUploadViewProjector;
import com.blogsystem.application.asset.upload.result.MediaUploadOperationView;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.application.music.service.MusicCoverBackfillApplicationService;
import com.blogsystem.trigger.http.support.ApiResponse;
import com.blogsystem.trigger.http.support.ExpectedVersionDeserializer;
import com.blogsystem.trigger.http.support.MediaUploadUuidDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/music/tracks")
@SaCheckRole("BLOG_OWNER")
@ConditionalOnExpression("'${media.catalog.storage:legacy-json}' == 'postgres' && ${media.catalog.uploads.enabled:false}")
public final class MusicCoverBackfillController {
    private final MusicCoverBackfillApplicationService service;
    private final MediaUploadViewProjector projector;
    private final IdentityApplicationFacade identity;
    public MusicCoverBackfillController(MusicCoverBackfillApplicationService service,
            MediaUploadViewProjector projector, IdentityApplicationFacade identity) {
        this.service=service; this.projector=projector; this.identity=identity;
    }
    public record Request(
            @JsonDeserialize(using=MediaUploadUuidDeserializer.class) UUID operationId,
            @JsonDeserialize(using=ExpectedVersionDeserializer.class) Long expectedVersion) {}

    @PostMapping("/{id}/cover/backfill")
    public ResponseEntity<ApiResponse<MediaUploadOperationView>> complete(@PathVariable String id, @RequestBody Request request) {
        var operation=service.complete(identity.requireCurrentOwner().id(),id,request.expectedVersion(),request.operationId());
        int code=switch(operation.progress().status()) {
            case SUCCEEDED->200;
            case CONFLICT->409;
            case FAILED->422;
            default->202;
        };
        var view=projector.view(operation);
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(
                code<400?ApiResponse.ok(view):new ApiResponse<>(code,"封面补全需要核对",view));
    }
}
