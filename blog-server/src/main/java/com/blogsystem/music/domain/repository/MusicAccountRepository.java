package com.blogsystem.music.domain.repository;

import com.blogsystem.music.domain.model.MusicAccount.*;
import com.blogsystem.music.domain.model.MusicPlaylist;

public interface MusicAccountRepository {
    Status status();

    Authorization authorize();

    Revocation revoke();

    MusicPlaylist synchronize(String configuredPlaylistId);
}
