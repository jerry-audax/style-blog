package com.blogsystem.domain.asset.publication.model.valobj;

import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;

/** Exact immutable publisher identity; release IDs remain case sensitive. */
public record MediaPublicationPinKey(String environmentId, String publisherId, String releaseId)
        implements Comparable<MediaPublicationPinKey> {
    public MediaPublicationPinKey {
        if (!matches(environmentId, "[a-z][a-z0-9-]{0,31}")
                || !matches(publisherId, "[a-z][a-z0-9-]{0,31}")
                || !matches(releaseId, "[A-Za-z0-9-]{1,64}")) {
            throw new MediaPublicationPinException(MediaPublicationPinException.Reason.INVALID_REQUEST);
        }
    }

    private static boolean matches(String value, String regex) { return value != null && value.matches(regex); }

    @Override public int compareTo(MediaPublicationPinKey other) {
        int result = environmentId.compareTo(other.environmentId);
        if (result == 0) result = publisherId.compareTo(other.publisherId);
        return result == 0 ? releaseId.compareTo(other.releaseId) : result;
    }
}
