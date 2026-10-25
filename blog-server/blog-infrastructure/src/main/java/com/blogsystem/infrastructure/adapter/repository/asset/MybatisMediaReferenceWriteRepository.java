package com.blogsystem.infrastructure.adapter.repository.asset;

import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceWriteException;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;
import com.blogsystem.domain.asset.reference.policy.MediaReferenceWritePolicy;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Database-only protection in the business writer's original transaction. No detached checks. */
public final class MybatisMediaReferenceWriteRepository implements MediaReferenceWriteRepository {
    private final MediaAssetRepository assets;
    private final Function<Set<UUID>, List<MediaAsset>> locks;
    private final MediaReferenceLinkGateway links;
    private final String provider;
    private final MediaObjectKey root;
    private final MediaReferenceWritePolicy policy = new MediaReferenceWritePolicy();

    public MybatisMediaReferenceWriteRepository(MediaAssetRepository assets,
            MediaDeletionAssetRepository locks, MediaReferenceLinkGateway links,
            String provider, MediaObjectKey root) {
        this(assets, deletionLocks(locks), links, provider, root);
    }

    private MybatisMediaReferenceWriteRepository(MediaAssetRepository assets,
            Function<Set<UUID>, List<MediaAsset>> locks, MediaReferenceLinkGateway links,
            String provider, MediaObjectKey root) {
        this.assets = Objects.requireNonNull(assets);
        this.locks = Objects.requireNonNull(locks);
        this.links = Objects.requireNonNull(links);
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("缺少存储提供方");
        this.provider = provider;
        this.root = Objects.requireNonNull(root);
    }

    /** 旧导入库只有基础资源列；复用保护规则，不读取或伪造删除归属字段。 */
    public static MybatisMediaReferenceWriteRepository forMetadataImport(MediaAssetRepository assets,
            MediaAssetMapper mapper, MediaAssetConverter converter, MediaReferenceLinkGateway links,
            String provider, MediaObjectKey root) {
        Objects.requireNonNull(mapper);
        Objects.requireNonNull(converter);
        return new MybatisMediaReferenceWriteRepository(assets,
                ids -> mapper.lockByIds(ids).stream().map(converter::toDomain).toList(), links, provider, root);
    }

    private static Function<Set<UUID>, List<MediaAsset>> deletionLocks(MediaDeletionAssetRepository locks) {
        Objects.requireNonNull(locks);
        return ids -> locks.lockByIds(ids).stream().map(snapshot -> snapshot.asset()).toList();
    }

    @Override
    public void lockForWrite(MediaReferenceWriteIntent previous, MediaReferenceWriteIntent desired) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        try {
            Objects.requireNonNull(previous);
            Objects.requireNonNull(desired);
            Set<MediaObjectKey> oldKeys = scan(previous, false);
            Set<MediaObjectKey> desiredKeys = scan(desired, true);
            Set<MediaObjectKey> allKeys = new HashSet<>(oldKeys);
            allKeys.addAll(desiredKeys);
            Map<MediaObjectKey, MediaAsset> found = locations(allKeys);
            Set<UUID> desiredIds = new HashSet<>(desired.assetIds());
            for (MediaObjectKey key : desiredKeys) {
                MediaAsset asset = found.get(key);
                if (asset == null) throw conflict();
                desiredIds.add(asset.id());
            }
            Set<UUID> allIds = new HashSet<>(previous.assetIds());
            allIds.addAll(desiredIds);
            Map<UUID, MediaAsset> resolved = new HashMap<>();
            for (MediaAsset asset : found.values()) {
                allIds.add(asset.id());
                if (resolved.putIfAbsent(asset.id(), asset) != null) throw unavailable();
            }
            List<UUID> ordered = allIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
            List<MediaAsset> protectedAssets = new ArrayList<>();
            for (int offset = 0; offset < ordered.size(); offset += 1000) {
                Set<UUID> batch = Set.copyOf(ordered.subList(offset, Math.min(offset + 1000, ordered.size())));
                var locked = locks.apply(batch);
                if (locked.size() != batch.size()) throw conflict();
                Set<UUID> seen = new HashSet<>();
                for (MediaAsset actual : locked) {
                    if (!batch.contains(actual.id()) || !seen.add(actual.id())) throw conflict();
                    MediaAsset expected = resolved.get(actual.id());
                    if (expected != null && (!actual.provider().equals(expected.provider())
                            || !actual.objectKey().equals(expected.objectKey()) || actual.kind() != expected.kind())) {
                        throw conflict();
                    }
                    if (desiredIds.contains(actual.id()) && !inScope(actual)) throw conflict();
                    protectedAssets.add(actual);
                }
            }
            policy.requireActive(desiredIds, protectedAssets);
        } catch (MediaReferenceWriteException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private Set<MediaObjectKey> scan(MediaReferenceWriteIntent intent, boolean required) {
        Set<MediaObjectKey> keys = new HashSet<>();
        for (String value : intent.links()) collect(keys, links.parseLink(value), required);
        for (String value : intent.contents()) collect(keys, links.parseContent(value), required);
        for (String value : intent.htmlContents()) collect(keys, links.parseHtmlContent(value), required);
        return keys;
    }

    private static void collect(Set<MediaObjectKey> keys, MediaLinkScan scan, boolean required) {
        if (required && !scan.complete()) throw conflict();
        keys.addAll(scan.objectKeys());
    }

    private Map<MediaObjectKey, MediaAsset> locations(Set<MediaObjectKey> keys) {
        List<MediaObjectKey> ordered = keys.stream().sorted(Comparator.comparing(MediaObjectKey::value)).toList();
        Map<MediaObjectKey, MediaAsset> result = new HashMap<>();
        for (int offset = 0; offset < ordered.size(); offset += 1000) {
            Set<MediaObjectKey> batch = Set.copyOf(ordered.subList(offset, Math.min(offset + 1000, ordered.size())));
            for (MediaAsset asset : assets.findByLocations(provider, batch)) {
                if (!inScope(asset) || !batch.contains(asset.objectKey())
                        || result.putIfAbsent(asset.objectKey(), asset) != null) throw unavailable();
            }
        }
        return result;
    }

    private boolean inScope(MediaAsset asset) {
        return asset.provider().equals(provider) && asset.objectKey().value().startsWith(root.value() + "/");
    }

    private static MediaReferenceWriteException conflict() {
        return new MediaReferenceWriteException(MediaReferenceWriteException.Reason.CONFLICT);
    }

    private static MediaReferenceWriteException unavailable() {
        return new MediaReferenceWriteException(MediaReferenceWriteException.Reason.UNAVAILABLE);
    }
}
