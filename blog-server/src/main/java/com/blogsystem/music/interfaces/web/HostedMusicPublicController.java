package com.blogsystem.music.interfaces.web;

import com.blogsystem.music.application.HostedMusicApplicationService;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/music")
public class HostedMusicPublicController {
    private final HostedMusicApplicationService music;

    public HostedMusicPublicController(HostedMusicApplicationService music) {
        this.music = music;
    }

    @GetMapping("/hosted-playlist")
    public ResponseEntity<ApiResponse<HostedMusicApplicationService.Playlist>> playlist() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(music.playlist()));
    }
}
