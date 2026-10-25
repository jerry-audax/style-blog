package com.blogsystem.infrastructure.mybatis.converter.asset;

import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.Item;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.ItemStatus;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.Reason;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionTarget;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaDeleteItemRecord;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaDeleteOperationRecord;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaDeletionAssetRecord;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public final class MediaDeletionConverter {
    private final MediaAssetConverter assets;

    public MediaDeletionConverter(MediaAssetConverter assets) {
        this.assets = assets;
    }

    public MediaDeletionAssetSnapshot asset(MediaDeletionAssetRecord row) {
        return row == null
                ? null
                : new MediaDeletionAssetSnapshot(
                        assets.toDomain(row),
                        ownership(
                                row.getDeletionScope(),
                                row.getExclusiveEnvironment(),
                                row.getOwnershipConfirmedBy(),
                                row.getOwnershipConfirmedAt(),
                                row.getOwnershipNote()));
    }

    public MediaDeleteOperationRecord operation(MediaDeletionOperation value) {
        var row = new MediaDeleteOperationRecord();
        row.setId(value.id());
        row.setActorId(value.actorId());
        row.setIdempotencyKey(value.idempotencyKey());
        row.setFingerprint(value.fingerprint());
        row.setTargetType(value.target().kind().name());
        row.setTargetId(value.target().id());
        row.setMediaSlot(value.target().slot() == null ? null : value.target().slot().name());
        row.setOriginalVersion(value.originalVersion());
        row.setCommittedAt(at(value.committedAt()));
        return row;
    }

    public MediaDeletionOperation operation(MediaDeleteOperationRecord row, List<Item> items) {
        return new MediaDeletionOperation(
                row.getId(),
                row.getActorId(),
                row.getIdempotencyKey(),
                row.getFingerprint().trim(),
                new MediaDeletionTarget(
                        MediaDeletionTarget.Kind.valueOf(row.getTargetType()),
                        row.getTargetId(),
                        row.getMediaSlot() == null
                                ? null
                                : MediaDeletionTarget.Slot.valueOf(row.getMediaSlot())),
                row.getOriginalVersion(),
                row.getCommittedAt().toInstant(),
                items);
    }

    public MediaDeleteItemRecord item(Item value, UUID operationId, Instant now) {
        var row = new MediaDeleteItemRecord();
        row.setId(value.id());
        row.setOperationId(operationId);
        row.setAssetId(value.assetId());
        row.setMediaKind(value.kind().name());
        row.setAssetVersion(value.assetVersion());
        row.setDeletionScopeSnapshot(value.ownership().scope().name());
        row.setExclusiveEnvironmentSnapshot(value.ownership().exclusiveEnvironment());
        row.setOwnershipConfirmedBySnapshot(value.ownership().confirmedBy());
        row.setOwnershipConfirmedAtSnapshot(at(value.ownership().confirmedAt()));
        row.setOwnershipNoteSnapshot(value.ownership().note());
        row.setStatus(value.status().name());
        row.setReason(value.reason().name());
        row.setLinkedItemId(value.linkedItemId());
        row.setCreatedAt(at(now));
        row.setUpdatedAt(at(now));
        return row;
    }

    public Item item(MediaDeleteItemRecord row, Item linked) {
        return new Item(
                row.getId(),
                row.getAssetId(),
                MediaKind.valueOf(row.getMediaKind()),
                row.getAssetVersion(),
                ownership(
                        row.getDeletionScopeSnapshot(),
                        row.getExclusiveEnvironmentSnapshot(),
                        row.getOwnershipConfirmedBySnapshot(),
                        row.getOwnershipConfirmedAtSnapshot(),
                        row.getOwnershipNoteSnapshot()),
                ItemStatus.valueOf(row.getStatus()),
                Reason.valueOf(row.getReason()),
                linked);
    }

    private MediaDeletionOwnership ownership(
            String scope, String environment, Long actor, OffsetDateTime time, String note) {
        return new MediaDeletionOwnership(
                MediaDeletionOwnership.Scope.valueOf(scope),
                environment,
                actor,
                time == null ? null : time.toInstant(),
                note);
    }

    public static OffsetDateTime at(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
