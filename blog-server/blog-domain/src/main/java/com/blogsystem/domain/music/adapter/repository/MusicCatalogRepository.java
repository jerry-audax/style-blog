package com.blogsystem.domain.music.adapter.repository;

import com.blogsystem.domain.music.model.MusicTrack;

import java.util.List;
import java.util.Optional;

public interface MusicCatalogRepository {
    Optional<List<MusicTrack>> find();

    void save(List<MusicTrack> tracks);
}
