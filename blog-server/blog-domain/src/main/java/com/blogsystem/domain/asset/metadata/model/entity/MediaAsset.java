package com.blogsystem.domain.asset.metadata.model.entity;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** A stable database identity and provider location for one managed resource. */
public record MediaAsset(
        UUID id,
        String provider,
        MediaObjectKey objectKey,
        MediaKind kind,
        MediaMetadata metadata,
        MediaAssetStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public MediaAsset {
        Objects.requireNonNull(id, "资源 ID 不能为空");
        requireProvider(provider);
        Objects.requireNonNull(objectKey, "资源路径不能为空");
        Objects.requireNonNull(kind, "资源类型不能为空");
        Objects.requireNonNull(metadata, "资源元数据不能为空");
        Objects.requireNonNull(status, "资源状态不能为空");
        if (version < 0) {
            throw new IllegalArgumentException("资源版本不能小于零");
        }
        createdAt = databasePrecision(createdAt, "创建时间");
        updatedAt = databasePrecision(updatedAt, "更新时间");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("更新时间不能早于创建时间");
        }
    }

    public static MediaAsset register(
            UUID id,
            String provider,
            MediaObjectKey objectKey,
            MediaKind kind,
            MediaMetadata metadata,
            Instant now) {
        Instant registeredAt = databasePrecision(now, "注册时间");
        return new MediaAsset(
                id,
                provider,
                objectKey,
                kind,
                metadata,
                MediaAssetStatus.ACTIVE,
                0,
                registeredAt,
                registeredAt);
    }

    public static MediaAsset restore(
            UUID id,
            String provider,
            MediaObjectKey objectKey,
            MediaKind kind,
            MediaMetadata metadata,
            MediaAssetStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new MediaAsset(
                id, provider, objectKey, kind, metadata, status, version, createdAt, updatedAt);
    }

    /** The repository increments the optimistic-lock version after a successful update. */
    public MediaAsset reviseMetadata(MediaMetadata next, Instant now) {
        Instant revisedAt = databasePrecision(now, "更新时间");
        if (revisedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("更新时间不能早于当前更新时间");
        }
        return new MediaAsset(
                id,
                provider,
                objectKey,
                kind,
                Objects.requireNonNull(next, "资源元数据不能为空"),
                status,
                version,
                createdAt,
                revisedAt);
    }

    private static Instant databasePrecision(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireProvider(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 32
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("存储提供方格式不合法");
        }
    }
}
