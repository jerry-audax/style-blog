package com.blogsystem.domain.music.gateway;

import com.blogsystem.domain.music.model.MusicMediaUpload;
import java.util.Optional;

/** Fetch an artwork candidate by a known song identity, never an arbitrary client URL. */
public interface SongArtworkDownloadGateway {
    Optional<MusicMediaUpload> download(String sourceId);
}
