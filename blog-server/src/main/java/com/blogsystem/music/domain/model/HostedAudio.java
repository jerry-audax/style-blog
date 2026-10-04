package com.blogsystem.music.domain.model;

import java.util.Set;

/**
 * Only public, sanitized metadata. No provider credentials or private upload metadata.
 */
public record HostedAudio(String id, String name, String url, String mediaType, long size) {
    public static final Set<String> MEDIA_TYPES = Set.of("audio/mpeg", "audio/mp3", "audio/mp4", "audio/m4a",
            "audio/x-m4a", "audio/aac", "audio/ogg", "audio/wav", "audio/x-wav", "audio/flac", "audio/webm");

    public HostedAudio {
        if (id == null || id.isBlank() || name == null || name.isBlank() || url == null || url.isBlank()
                || mediaType == null || !MEDIA_TYPES.contains(mediaType) || size < 0)
            throw new IllegalArgumentException("Invalid hosted audio");
    }
}
