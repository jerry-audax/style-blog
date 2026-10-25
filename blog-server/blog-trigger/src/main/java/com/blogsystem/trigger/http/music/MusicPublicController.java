package com.blogsystem.trigger.http.music;

import com.blogsystem.application.music.service.MusicApplicationService;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/music")
public class MusicPublicController {
    private final MusicApplicationService music;

    public MusicPublicController(MusicApplicationService music) {
        this.music = music;
    }

    @GetMapping("/playlist")
    public ResponseEntity<ApiResponse<MusicApplicationService.PublicPlaylist>> playlist() {
        // No CLI calls on visitor requests; no user-selected resource ID or owner access token.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(music.publicPlaylist().orElse(null)));
    }
}
