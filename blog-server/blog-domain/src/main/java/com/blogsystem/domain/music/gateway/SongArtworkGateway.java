package com.blogsystem.domain.music.gateway;

import java.util.Map;
import java.util.Set;

/**
 * Optional public artwork. Failure must not prevent playback of hosted audio.
 */
public interface SongArtworkGateway {
    Map<String, String> findCovers(Set<String> songIds);
}
