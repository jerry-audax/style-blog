package com.blogsystem.infrastructure.mybatis.converter.asset;

import com.blogsystem.domain.asset.publication.model.entity.MediaPublicationPin;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaPublicationPinRecord;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

public final class MediaPublicationPinConverter {
    public MediaPublicationPinRecord toRecord(MediaPublicationPin pin) {
        var row = new MediaPublicationPinRecord();
        row.setEnvironmentId(pin.key().environmentId()); row.setPublisherId(pin.key().publisherId()); row.setReleaseId(pin.key().releaseId());
        row.setManifestDigest(pin.manifestDigest());
        row.setAssetIds(pin.assetIds().stream().sorted(java.util.Comparator.comparing(UUID::toString)).toArray(UUID[]::new));
        row.setStatus(pin.status().name()); row.setVersion(pin.version());
        row.setCreatedAt(at(pin.createdAt())); row.setUpdatedAt(at(pin.updatedAt())); row.setReleasedAt(at(pin.releasedAt()));
        return row;
    }

    public MediaPublicationPin toDomain(MediaPublicationPinRecord row) {
        if (row == null) return null;
        return new MediaPublicationPin(new MediaPublicationPinKey(row.getEnvironmentId(), row.getPublisherId(), row.getReleaseId()),
                row.getManifestDigest(), new HashSet<>(Arrays.asList(row.getAssetIds())), MediaPublicationPin.Status.valueOf(row.getStatus()),
                row.getVersion(), row.getCreatedAt().toInstant(), row.getUpdatedAt().toInstant(),
                row.getReleasedAt() == null ? null : row.getReleasedAt().toInstant());
    }

    public static OffsetDateTime at(Instant instant) { return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
