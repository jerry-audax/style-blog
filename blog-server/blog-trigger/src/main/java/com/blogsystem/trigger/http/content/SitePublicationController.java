package com.blogsystem.trigger.http.content;

import cn.dev33.satoken.annotation.SaCheckRole;

import com.blogsystem.application.content.service.SitePublicationApplicationService;
import com.blogsystem.domain.content.query.SitePublicationView;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/publication")
@SaCheckRole("BLOG_OWNER")
public class SitePublicationController {
    private final SitePublicationApplicationService publication;

    public SitePublicationController(SitePublicationApplicationService publication) {
        this.publication = publication;
    }

    @GetMapping("/status")
    public ApiResponse<SitePublicationView> status() {
        return ApiResponse.ok(publication.status());
    }

    @PostMapping("/retry")
    public ResponseEntity<ApiResponse<SitePublicationView>> retry() {
        return ResponseEntity.accepted().body(ApiResponse.ok(publication.retry()));
    }
}
