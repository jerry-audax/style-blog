package com.blogsystem.domain.music.gateway;

import com.blogsystem.domain.music.model.HostedAudio;

import java.util.List;

public interface HostedAudioGateway {
    /**
     * Complete list of public audio in the configured publishing namespace, never a partial page.
     */
    List<HostedAudio> findPublished();

    /** Invalidate provider-side discovery after a managed upload or deletion. */
    default void invalidate() {}
}
