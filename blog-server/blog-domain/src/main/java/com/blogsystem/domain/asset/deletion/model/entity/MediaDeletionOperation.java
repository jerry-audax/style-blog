package com.blogsystem.domain.asset.deletion.model.entity;

import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionTarget;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A committed local removal and its per-asset results, not a promise of physical deletion. */
public record MediaDeletionOperation(
        UUID id,
        long actorId,
        String idempotencyKey,
        String fingerprint,
        MediaDeletionTarget target,
        long originalVersion,
        Instant committedAt,
        List<Item> items) {
    public enum ItemStatus {
        PENDING,
        PROCESSING,
        SUCCEEDED,
        RETRY_WAIT,
        FAILED,
        UNKNOWN,
        RETAINED,
        LINKED
    }

    public enum Reason {
        NONE,
        OWNERSHIP_UNVERIFIED,
        SHARED,
        ENVIRONMENT_MISMATCH,
        REFERENCE_COVERAGE_INCOMPLETE,
        IN_USE,
        WRITE_GUARDS_INCOMPLETE,
        ASSET_NOT_ACTIVE,
        PROVIDER_FAILURE,
        OUTCOME_UNKNOWN
    }

    public MediaDeletionOperation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(target);
        Objects.requireNonNull(committedAt);
        if (actorId <= 0
                || originalVersion < 0
                || idempotencyKey == null
                || !idempotencyKey.matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || fingerprint == null
                || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("无效删除回执");
        committedAt = committedAt.truncatedTo(ChronoUnit.MICROS);
        items = List.copyOf(items);
        Set<UUID> assets = new HashSet<>(), ids = new HashSet<>();
        for (Item item : items)
            if (!assets.add(item.assetId()) || !ids.add(item.id()))
                throw new IllegalArgumentException("资源处理项重复");
    }

    public boolean processingComplete() {
        return items.stream()
                .allMatch(
                        item ->
                                switch (item.resolvedStatus()) {
                                    case SUCCEEDED, FAILED, RETAINED -> true;
                                    default -> false;
                                });
    }

    public boolean allFilesDeleted() {
        return !items.isEmpty()
                && items.stream().allMatch(item -> item.resolvedStatus() == ItemStatus.SUCCEEDED);
    }

    public record Item(
            UUID id,
            UUID assetId,
            MediaKind kind,
            long assetVersion,
            MediaDeletionOwnership ownership,
            ItemStatus status,
            Reason reason,
            Item linkedItem) {
        public Item {
            Objects.requireNonNull(id);
            Objects.requireNonNull(assetId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(ownership);
            Objects.requireNonNull(status);
            Objects.requireNonNull(reason);
            if (assetVersion < 0 || (status == ItemStatus.LINKED) != (linkedItem != null))
                throw new IllegalArgumentException("无效资源处理项");
            if (linkedItem != null
                    && (id.equals(linkedItem.id())
                            || !assetId.equals(linkedItem.assetId())
                            || kind != linkedItem.kind()
                            || linkedItem.status() == ItemStatus.LINKED))
                throw new IllegalArgumentException("关联必须指向同资源的单层执行项");
            boolean retentionReason =
                    switch (reason) {
                        case OWNERSHIP_UNVERIFIED,
                                        SHARED,
                                        ENVIRONMENT_MISMATCH,
                                        REFERENCE_COVERAGE_INCOMPLETE,
                                        IN_USE,
                                        WRITE_GUARDS_INCOMPLETE,
                                        ASSET_NOT_ACTIVE ->
                                true;
                        default -> false;
                    };
            boolean valid =
                    switch (status) {
                        case RETAINED -> retentionReason;
                        case UNKNOWN -> reason == Reason.OUTCOME_UNKNOWN;
                        case FAILED, RETRY_WAIT -> reason == Reason.PROVIDER_FAILURE;
                        default -> reason == Reason.NONE;
                    };
            if (!valid) throw new IllegalArgumentException("状态与原因不匹配");
        }

        public UUID linkedItemId() {
            return linkedItem == null ? null : linkedItem.id();
        }

        public ItemStatus resolvedStatus() {
            return linkedItem == null ? status : linkedItem.status();
        }

        public Reason resolvedReason() {
            return linkedItem == null ? reason : linkedItem.reason();
        }
    }
}
