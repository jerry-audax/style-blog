package com.blogsystem.music.domain.repository;

import com.blogsystem.music.domain.model.MusicMedia;
import com.blogsystem.music.domain.model.MusicMediaUpload;

public interface MusicMediaRepository {
    MusicMedia upload(MusicMediaUpload upload);

    void delete(String path);

    String publicUrl(String path);
}
