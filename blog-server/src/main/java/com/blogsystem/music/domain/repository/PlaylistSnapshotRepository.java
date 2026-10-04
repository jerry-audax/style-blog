package com.blogsystem.music.domain.repository;

import com.blogsystem.music.domain.model.MusicPlaylist;

import java.util.Optional;

public interface PlaylistSnapshotRepository {
    Optional<MusicPlaylist> find();

    void save(MusicPlaylist playlist);

    void clear();
}
