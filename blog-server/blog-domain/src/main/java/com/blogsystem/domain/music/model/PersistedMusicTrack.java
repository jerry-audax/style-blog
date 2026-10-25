package com.blogsystem.domain.music.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

/** Existing catalogue identity and stable asset references for metadata import. */
public record PersistedMusicTrack(
        String id,
        String title,
        String artist,
        String sourceId,
        UUID audioAssetId,
        UUID coverAssetId,
        UUID lyricsAssetId,
        String externalCoverUrl,
        boolean enabled,
        int sortOrder) {

    public PersistedMusicTrack {
        requireText(id, "Music track ID", 64, false);
        requireText(title, "Music title", 200, false);
        requireText(artist, "Music artist", 200, true);
        if (sourceId != null && sourceId.length() > 64) {
            throw new IllegalArgumentException("Music source ID exceeds 64 characters");
        }
        if (sortOrder < 0 || sortOrder > 100000) {
            throw new IllegalArgumentException("Invalid music sort order");
        }
        requireExternalCoverUrl(externalCoverUrl);
    }

    private static void requireText(String value, String field, int maxLength, boolean allowEmpty) {
        if (value == null || value.length() > maxLength || (!allowEmpty && value.isBlank())) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void requireExternalCoverUrl(String value) {
        if (value == null) {
            return;
        }
        try {
            URI uri = new URI(value);
            if ((!"http".equalsIgnoreCase(uri.getScheme())
                            && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException(
                        "External music artwork requires an HTTP(S) URL without user information");
            }
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid external music artwork URL", invalid);
        }
    }
}
