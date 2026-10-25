package com.blogsystem.domain.music.adapter.repository;

import com.blogsystem.domain.music.model.MusicPlaylist;

import java.util.Optional;

public interface PlaylistSnapshotRepository {
    Optional<MusicPlaylist> find();

    void save(MusicPlaylist playlist);

    void clear();
}
