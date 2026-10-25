package com.blogsystem.domain.music.gateway;

import com.blogsystem.domain.music.model.MusicAccount.Authorization;
import com.blogsystem.domain.music.model.MusicAccount.Revocation;
import com.blogsystem.domain.music.model.MusicAccount.Status;
import com.blogsystem.domain.music.model.MusicPlaylist;

public interface MusicAccountGateway {
    Status status();

    Authorization authorize();

    Revocation revoke();

    MusicPlaylist synchronize(String configuredPlaylistId);
}
