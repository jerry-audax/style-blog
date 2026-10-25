package com.blogsystem.trigger.http.music;

import com.blogsystem.application.music.port.HostedMusicQueryUseCase;
import com.blogsystem.application.music.service.HostedMusicApplicationService;
import com.blogsystem.trigger.http.support.ApiResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/music")
public class HostedMusicPublicController {
    private final HostedMusicQueryUseCase music;

    public HostedMusicPublicController(HostedMusicQueryUseCase music) {
        this.music = music;
    }

    @GetMapping("/hosted-playlist")
    public ResponseEntity<ApiResponse<HostedMusicApplicationService.Playlist>> playlist() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(music.playlist()));
    }
}
