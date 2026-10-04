package com.blogsystem.music.domain.repository;

import java.util.Map;
import java.util.Set;

/**
 * Optional public artwork. Failure must not prevent playback of hosted audio.
 */
public interface SongArtworkRepository {
    Map<String, String> findCovers(Set<String> songIds);
}
