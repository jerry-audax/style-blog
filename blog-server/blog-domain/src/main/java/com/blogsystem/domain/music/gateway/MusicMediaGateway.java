package com.blogsystem.domain.music.gateway;

import com.blogsystem.domain.music.model.MusicMedia;
import com.blogsystem.domain.music.model.MusicMediaUpload;

public interface MusicMediaGateway {
    MusicMedia upload(MusicMediaUpload upload);

    void delete(String path);

    String publicUrl(String path);
}
