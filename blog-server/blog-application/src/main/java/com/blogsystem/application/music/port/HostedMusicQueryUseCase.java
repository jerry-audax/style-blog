package com.blogsystem.application.music.port;

import com.blogsystem.application.music.service.HostedMusicApplicationService;

public interface HostedMusicQueryUseCase {
    HostedMusicApplicationService.Playlist playlist();
}
