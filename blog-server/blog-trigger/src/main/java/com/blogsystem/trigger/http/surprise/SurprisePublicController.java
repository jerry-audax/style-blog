package com.blogsystem.trigger.http.surprise;

import com.blogsystem.application.surprise.port.SurpriseVideoUseCase;
import com.blogsystem.application.surprise.service.SurpriseVideoApplicationService;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/surprise")
public class SurprisePublicController {
    private final SurpriseVideoUseCase service;

    public SurprisePublicController(SurpriseVideoUseCase service) {
        this.service = service;
    }

    @GetMapping("/random")
    public ResponseEntity<ApiResponse<SurpriseVideoApplicationService.VideoView>> random() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.randomPublic()));
    }
}
