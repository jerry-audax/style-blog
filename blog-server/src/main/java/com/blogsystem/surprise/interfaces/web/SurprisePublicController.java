package com.blogsystem.surprise.interfaces.web;

import com.blogsystem.shared.interfaces.http.ApiResponse;
import com.blogsystem.surprise.application.SurpriseVideoApplicationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/surprise")
public class SurprisePublicController {
    private final SurpriseVideoApplicationService service;
    public SurprisePublicController(SurpriseVideoApplicationService service) { this.service = service; }
    @GetMapping("/random")
    public ResponseEntity<ApiResponse<SurpriseVideoApplicationService.VideoView>> random() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.randomPublic()));
    }
}
