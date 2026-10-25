package com.blogsystem.domain.asset.publication.adapter.repository;

import com.blogsystem.domain.asset.publication.model.entity.MediaPublicationPin;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable publication lifecycle, not an inferred cache of current business references. */
public interface MediaPublicationPinRepository {
    MediaPublicationPin insertHeld(MediaPublicationPin pin);
    Optional<MediaPublicationPin> findByKey(MediaPublicationPinKey key);
    /** All environments/publishers, HELD only, in stable key order. */
    List<MediaPublicationPin> findHolding(UUID assetId);
    MediaPublicationPin release(MediaPublicationPinKey key, long expectedVersion, Instant now);
}
