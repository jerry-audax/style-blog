package com.blogsystem.domain.asset.publication.model.entity;

import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/** A release retains its manifest even after retirement; a HELD pin never expires. */
public record MediaPublicationPin(MediaPublicationPinKey key, String manifestDigest, Set<UUID> assetIds,
        Status status, long version, Instant createdAt, Instant updatedAt, Instant releasedAt) {
    public enum Status { HELD, RELEASED }

    public MediaPublicationPin {
        if (key == null || manifestDigest == null || !manifestDigest.matches("[0-9a-f]{64}")
                || assetIds == null || assetIds.size() > 10000 || assetIds.stream().anyMatch(id -> id == null)
                || status == null || version < 0) throw invalid();
        assetIds = Set.copyOf(assetIds);
        createdAt = precision(createdAt);
        updatedAt = precision(updatedAt);
        if (updatedAt.isBefore(createdAt)) throw invalid();
        if (status == Status.HELD && releasedAt != null) throw invalid();
        if (status == Status.RELEASED) {
            releasedAt = precision(releasedAt);
            if (!releasedAt.equals(updatedAt)) throw invalid();
        }
    }

    public static MediaPublicationPin held(MediaPublicationPinKey key, String digest, Set<UUID> ids, Instant now) {
        return new MediaPublicationPin(key, digest, ids, Status.HELD, 0, now, now, null);
    }

    public MediaPublicationPin release(long expectedVersion, Instant now) {
        if (status != Status.HELD || expectedVersion != version || version == Long.MAX_VALUE) {
            throw new MediaPublicationPinException(MediaPublicationPinException.Reason.CONFLICT);
        }
        Instant at = precision(now);
        if (at.isBefore(updatedAt)) throw invalid();
        return new MediaPublicationPin(key, manifestDigest, assetIds, Status.RELEASED, version + 1, createdAt, at, at);
    }

    private static Instant precision(Instant time) {
        if (time == null) throw invalid();
        return time.truncatedTo(ChronoUnit.MICROS);
    }

    private static MediaPublicationPinException invalid() {
        return new MediaPublicationPinException(MediaPublicationPinException.Reason.INVALID_REQUEST);
    }
}
