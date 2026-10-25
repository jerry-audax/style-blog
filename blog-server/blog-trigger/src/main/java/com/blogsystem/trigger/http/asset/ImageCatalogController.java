package com.blogsystem.trigger.http.asset;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.blogsystem.application.asset.image.result.ImageCatalogPageView;
import com.blogsystem.application.asset.image.service.ImageCatalogQueryApplicationService;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.trigger.http.support.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/media/images")
@SaCheckRole("BLOG_OWNER")
@ConditionalOnProperty(name = "media.catalog.storage", havingValue = "postgres")
public final class ImageCatalogController {
    private final ImageCatalogQueryApplicationService images;
    private final IdentityApplicationFacade identity;

    public ImageCatalogController(ImageCatalogQueryApplicationService images, IdentityApplicationFacade identity) {
        this.images = images; this.identity = identity;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ImageCatalogPageView>> list(@RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int count, @RequestParam(defaultValue = "") String search) {
        identity.requireCurrentOwner();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(images.findPage(offset, count, search)));
    }
}
