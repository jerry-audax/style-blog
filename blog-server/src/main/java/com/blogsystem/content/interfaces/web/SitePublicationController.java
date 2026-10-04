package com.blogsystem.content.interfaces.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.blogsystem.content.application.SitePublicationApplicationService;
import com.blogsystem.content.domain.query.SitePublicationView;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/publication")
@SaCheckPermission("content:article:write")
public class SitePublicationController {
    private final SitePublicationApplicationService publication;
    public SitePublicationController(SitePublicationApplicationService publication) {this.publication = publication;}
    @GetMapping("/status")
    public ApiResponse<SitePublicationView> status() {return ApiResponse.ok(publication.status());}
    @PostMapping("/retry")
    public ResponseEntity<ApiResponse<SitePublicationView>> retry() {return ResponseEntity.accepted().body(ApiResponse.ok(publication.retry()));}
}
