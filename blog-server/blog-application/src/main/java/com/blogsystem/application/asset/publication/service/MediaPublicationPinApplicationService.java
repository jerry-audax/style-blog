package com.blogsystem.application.asset.publication.service;

import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationPinRepository;
import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException.Reason;
import com.blogsystem.domain.asset.publication.model.entity.MediaPublicationPin;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import com.blogsystem.domain.asset.publication.model.valobj.ResolvedMediaPublicationManifest;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationResourceIdentity;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Owns short database transactions; publication rendering and retirement happen outside this use case. */
public final class MediaPublicationPinApplicationService {
    private final MediaPublicationPinRepository pins;
    private final MediaDeletionAssetRepository assets;
    private final TransactionOperations transactions;
    private final Clock clock;

    public MediaPublicationPinApplicationService(MediaPublicationPinRepository pins, MediaDeletionAssetRepository assets,
            TransactionOperations transactions, Clock clock) {
        this.pins = Objects.requireNonNull(pins);
        this.assets = Objects.requireNonNull(assets);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    public MediaPublicationPin prepare(MediaPublicationPinKey key, String digest, Set<UUID> ids) {
        return prepareWithIdentities(key, digest, ids, Map.of());
    }

    public MediaPublicationPin prepareVerified(MediaPublicationPinKey key, ResolvedMediaPublicationManifest manifest) {
        if (manifest == null) throw error(Reason.INVALID_REQUEST);
        Map<UUID, MediaPublicationResourceIdentity> identities = manifest.resources().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(MediaPublicationResourceIdentity::id, value -> value));
        return prepareWithIdentities(key, manifest.manifestDigest(), identities.keySet(), identities);
    }

    private MediaPublicationPin prepareWithIdentities(MediaPublicationPinKey key, String digest, Set<UUID> ids,
            Map<UUID, MediaPublicationResourceIdentity> identities) {
        requireOwnTransaction();
        MediaPublicationPin requested = MediaPublicationPin.held(key, digest, ids, clock.instant());
        try {
            return inTransaction(() -> {
                lockAssets(requested.assetIds(), true, identities);
                var prior = pins.findByKey(key);
                return prior.isPresent() ? replayPrepare(prior.get(), requested) : pins.insertHeld(requested);
            });
        } catch (MediaPublicationPinException failure) {
            if (failure.reason() != Reason.CONFLICT) throw failure;
            // The failed INSERT's transaction has exited and rolled back before any replay query.
            return inTransaction(() -> {
                lockAssets(requested.assetIds(), true, identities);
                return replayPrepare(pins.findByKey(key).orElseThrow(() -> error(Reason.CONFLICT)), requested);
            });
        }
    }

    public MediaPublicationPin release(MediaPublicationPinKey key, long expectedVersion, String expectedDigest) {
        requireOwnTransaction();
        MediaPublicationPin.held(key, expectedDigest, Set.of(), clock.instant());
        if (expectedVersion < 0) throw error(Reason.INVALID_REQUEST);
        try {
            return inTransaction(() -> {
                MediaPublicationPin initial = required(key);
                lockAssets(initial.assetIds(), false);
                MediaPublicationPin current = required(key);
                requireDigest(current, expectedDigest);
                if (isReleasedReplay(current, expectedVersion)) return current;
                current.release(expectedVersion, clock.instant());
                return pins.release(key, expectedVersion, clock.instant());
            });
        } catch (MediaPublicationPinException failure) {
            if (failure.reason() != Reason.CONFLICT) throw failure;
            // Empty manifests have no asset row to serialize concurrent releases; re-read the CAS winner.
            return inTransaction(() -> {
                MediaPublicationPin current = required(key);
                requireDigest(current, expectedDigest);
                if (!isReleasedReplay(current, expectedVersion)) throw error(Reason.CONFLICT);
                return current;
            });
        }
    }

    public MediaPublicationPin get(MediaPublicationPinKey key) { return inTransaction(() -> required(key)); }
    public List<MediaPublicationPin> holding(UUID assetId) { return inTransaction(() -> pins.findHolding(assetId)); }

    public void verifyHeld(MediaPublicationPinKey key, String expectedDigest, ResolvedMediaPublicationManifest output) {
        requireOwnTransaction();
        if (output == null) throw error(Reason.INVALID_REQUEST);
        MediaPublicationPin.held(key, expectedDigest, Set.of(), clock.instant());
        var identities = output.resources().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                MediaPublicationResourceIdentity::id, value -> value));
        inTransaction(() -> {
            var initial = required(key);
            requireHeldDigest(initial, expectedDigest);
            if (!initial.assetIds().containsAll(identities.keySet())) throw error(Reason.CONFLICT);
            lockAssets(initial.assetIds(), true, identities);
            // 释放也遵循相同资源锁顺序；等待后重读，不能用等待前的HELD状态批准输出。
            requireHeldDigest(required(key), expectedDigest);
            return null;
        });
    }

    private static void requireHeldDigest(MediaPublicationPin pin, String digest) {
        requireDigest(pin, digest);
        if (pin.status() != MediaPublicationPin.Status.HELD) throw error(Reason.CONFLICT);
    }

    private MediaPublicationPin required(MediaPublicationPinKey key) {
        return pins.findByKey(key).orElseThrow(() -> error(Reason.NOT_FOUND));
    }

    private static MediaPublicationPin replayPrepare(MediaPublicationPin current, MediaPublicationPin requested) {
        if (current.status() != MediaPublicationPin.Status.HELD || !current.manifestDigest().equals(requested.manifestDigest())
                || !current.assetIds().equals(requested.assetIds())) throw error(Reason.CONFLICT);
        return current;
    }

    private static void requireDigest(MediaPublicationPin pin, String digest) {
        if (!pin.manifestDigest().equals(digest)) throw error(Reason.CONFLICT);
    }

    private static boolean isReleasedReplay(MediaPublicationPin current, long expectedVersion) {
        return current.status() == MediaPublicationPin.Status.RELEASED && expectedVersion < Long.MAX_VALUE
                && current.version() == expectedVersion + 1;
    }

    private void lockAssets(Set<UUID> ids, boolean requireActive) {
        lockAssets(ids, requireActive, Map.of());
    }

    private void lockAssets(Set<UUID> ids, boolean requireActive, Map<UUID, MediaPublicationResourceIdentity> identities) {
        List<UUID> ordered = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        for (int offset = 0; offset < ordered.size(); offset += 1000) {
            Set<UUID> batch = new HashSet<>(ordered.subList(offset, Math.min(offset + 1000, ordered.size())));
            var locked = assets.lockByIds(batch);
            if (locked.size() != batch.size() || locked.stream().anyMatch(value -> !batch.contains(value.asset().id())
                    || requireActive && value.asset().status() != MediaAssetStatus.ACTIVE)) throw error(Reason.CONFLICT);
            // 解析到的UUID不是永久定位承诺；取得锁后复核原始身份，拒绝迟到的旧清单。
            for (var snapshot : locked) {
                var asset = snapshot.asset();
                var expected = identities.get(asset.id());
                if (expected != null && (!expected.provider().equals(asset.provider())
                        || !expected.objectKey().equals(asset.objectKey()) || expected.kind() != asset.kind()))
                    throw error(Reason.CONFLICT);
            }
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        try { return transactions.execute(status -> action.get()); }
        catch (MediaDeletionException | TransactionException failure) { throw error(Reason.UNAVAILABLE); }
    }

    private static void requireOwnTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw error(Reason.INVALID_REQUEST);
    }

    private static MediaPublicationPinException error(Reason reason) { return new MediaPublicationPinException(reason); }
}
