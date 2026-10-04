package com.blogsystem.surprise.domain.repository;

import com.blogsystem.surprise.domain.model.SurpriseVideo;
import java.util.List;
import java.util.Optional;

public interface SurpriseVideoRepository {
    List<SurpriseVideo> findAll();
    Optional<SurpriseVideo> findById(String id);
    SurpriseVideo save(SurpriseVideo video);
    void delete(String id);
}
