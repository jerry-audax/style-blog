package com.blogsystem.music.domain.repository;

import com.blogsystem.music.domain.model.HostedAudio;

import java.util.List;

public interface HostedAudioRepository {
    /**
     * Complete list of public audio in the configured publishing namespace, never a partial page.
     */
    List<HostedAudio> findPublished();

    /** Invalidate provider-side discovery after a managed upload or deletion. */
    default void invalidate() { }
}
