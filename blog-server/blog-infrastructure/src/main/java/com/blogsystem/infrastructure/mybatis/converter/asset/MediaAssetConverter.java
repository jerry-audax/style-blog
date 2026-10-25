package com.blogsystem.infrastructure.mybatis.converter.asset;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaAssetRecord;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Maps the PostgreSQL shape without leaking it into domain contracts. */
@Component
public final class MediaAssetConverter {
    public MediaAssetRecord toRecord(MediaAsset asset) {
        MediaAssetRecord record = new MediaAssetRecord();
        record.setId(asset.id());
        record.setProvider(asset.provider());
        record.setObjectKey(asset.objectKey().value());
        record.setMediaKind(asset.kind().name());
        record.setFileName(asset.metadata().fileName());
        record.setMimeType(asset.metadata().mimeType());
        record.setFileSize(asset.metadata().fileSize());
        record.setSha256(asset.metadata().sha256());
        record.setWidth(asset.metadata().width());
        record.setHeight(asset.metadata().height());
        record.setDurationMs(asset.metadata().durationMs());
        record.setStatus(asset.status().name());
        record.setVersion(asset.version());
        record.setCreatedAt(OffsetDateTime.ofInstant(asset.createdAt(), ZoneOffset.UTC));
        record.setUpdatedAt(OffsetDateTime.ofInstant(asset.updatedAt(), ZoneOffset.UTC));
        return record;
    }

    public MediaAsset toDomain(MediaAssetRecord record) {
        if (record == null) {
            return null;
        }
        return MediaAsset.restore(
                record.getId(),
                record.getProvider(),
                new MediaObjectKey(record.getObjectKey()),
                MediaKind.valueOf(record.getMediaKind()),
                new MediaMetadata(
                        record.getFileName(),
                        record.getMimeType(),
                        record.getFileSize(),
                        record.getSha256() == null ? null : record.getSha256().trim(),
                        record.getWidth(),
                        record.getHeight(),
                        record.getDurationMs()),
                MediaAssetStatus.valueOf(record.getStatus()),
                record.getVersion(),
                record.getCreatedAt().toInstant(),
                record.getUpdatedAt().toInstant());
    }
}
