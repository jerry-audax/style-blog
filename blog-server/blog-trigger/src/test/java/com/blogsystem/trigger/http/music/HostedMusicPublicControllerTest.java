package com.blogsystem.trigger.http.music;

import com.blogsystem.trigger.http.music.HostedMusicPublicController;
import com.blogsystem.trigger.http.music.MusicExceptionHandler;

import com.blogsystem.application.music.service.HostedMusicApplicationService.Playlist;
import com.blogsystem.application.music.service.HostedMusicApplicationService.Song;

import org.mockito.Mockito;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.blogsystem.application.music.service.HostedMusicApplicationService;
import com.blogsystem.domain.music.model.HostedMusicUnavailable;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HostedMusicPublicControllerTest {
    @Test
    void anonymousPlaylistContainsOnlyPublicFieldsAndNoCredentials() throws Exception {
        var music = mock(HostedMusicApplicationService.class);
        when(music.playlist()).thenReturn(new HostedMusicApplicationService.Playlist("imgbed", "博客音乐", List.of(
                new HostedMusicApplicationService.Song("blog/music/a.mp3", "歌曲", "本站音频", "", "https://example.test/file/blog/music/a.mp3", "audio/mpeg", 10))));
        var mvc = MockMvcBuilders.standaloneSetup(new HostedMusicPublicController(music)).setControllerAdvice(new MusicExceptionHandler()).build();
        mvc.perform(get("/api/music/hosted-playlist")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.source").value("imgbed"))
                .andExpect(jsonPath("$.data.songs[0].name").value("歌曲"))
                .andExpect(jsonPath("$.data.songs[0].TgFileId").doesNotExist())
                .andExpect(jsonPath("$.data.apiToken").doesNotExist())
                .andExpect(jsonPath("$.data.authorizationUrl").doesNotExist());
    }

    @Test
    void unavailableProviderIs503AndUnexpectedDiagnosticsAreNotReturned() throws Exception {
        var music = mock(HostedMusicApplicationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new HostedMusicPublicController(music)).setControllerAdvice(new MusicExceptionHandler()).build();
        when(music.playlist()).thenThrow(new HostedMusicUnavailable());
        mvc.perform(get("/api/music/hosted-playlist")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("本站音频歌单暂时不可用，请稍后重试"));
        doThrow(new RuntimeException("token=secret")).when(music).playlist();
        mvc.perform(get("/api/music/hosted-playlist")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("音乐操作失败，请稍后检查状态"));
    }
}
