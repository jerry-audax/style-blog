package com.blogsystem.music.domain.repository;

import com.blogsystem.music.domain.model.MusicTrack;

import java.util.List;
import java.util.Optional;

public interface MusicCatalogRepository {
    Optional<List<MusicTrack>> find();

    void save(List<MusicTrack> tracks);
}
