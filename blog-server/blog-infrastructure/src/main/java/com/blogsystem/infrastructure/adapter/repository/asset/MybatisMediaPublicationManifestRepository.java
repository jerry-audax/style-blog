package com.blogsystem.infrastructure.adapter.repository.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationManifestRepository;
import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationManifestInput;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationResourceIdentity;
import com.blogsystem.domain.asset.publication.model.valobj.ResolvedMediaPublicationManifest;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves immutable resource identities only; ACTIVE and identity are rechecked under prepare locks. */
public final class MybatisMediaPublicationManifestRepository implements MediaPublicationManifestRepository {
    private final MediaAssetRepository assets;
    private final MediaReferenceLinkGateway links;
    private final String provider;
    private final MediaObjectKey root;
    private final ObjectMapper json = new ObjectMapper();

    public MybatisMediaPublicationManifestRepository(MediaAssetRepository assets, MediaReferenceLinkGateway links,
            String provider, MediaObjectKey root) {
        this.assets = Objects.requireNonNull(assets); this.links = Objects.requireNonNull(links);
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("缺少存储提供方");
        this.provider = provider; this.root = Objects.requireNonNull(root);
    }

    @Override public ResolvedMediaPublicationManifest resolve(MediaPublicationManifestInput input) {
        if (input == null) throw error(MediaPublicationPinException.Reason.INVALID_REQUEST);
        try {
            Set<MediaObjectKey> keys = new HashSet<>();
            for (String value : input.links()) collect(keys, links.parseLink(value));
            for (String value : input.contents()) collect(keys, links.parseContent(value));
            for (String value : input.htmlContents()) collect(keys, links.parseHtmlContent(value));
            var ordered = keys.stream().sorted(Comparator.comparing(MediaObjectKey::value)).toList();
            Map<MediaObjectKey, MediaPublicationResourceIdentity> found = new HashMap<>();
            for (int offset = 0; offset < ordered.size(); offset += 1000) {
                var batch = Set.copyOf(ordered.subList(offset, Math.min(offset + 1000, ordered.size())));
                for (var asset : assets.findByLocations(provider, batch)) {
                    if (!asset.provider().equals(provider) || !asset.objectKey().value().startsWith(root.value() + "/")
                            || !batch.contains(asset.objectKey()) || found.putIfAbsent(asset.objectKey(),
                            new MediaPublicationResourceIdentity(asset.id(), asset.provider(), asset.objectKey(), asset.kind())) != null)
                        throw error(MediaPublicationPinException.Reason.UNAVAILABLE);
                }
            }
            if (found.size() != keys.size()) throw error(MediaPublicationPinException.Reason.CONFLICT);
            var identities = Set.copyOf(found.values());
            // 固定字段顺序和UUID顺序；相同对象的域名别名不会改变指纹，原始URL不持久化。
            var canonical = new LinkedHashMap<String, Object>();
            canonical.put("protocolVersion", 1); canonical.put("sourceDigest", input.sourceDigest());
            canonical.put("resources", identities.stream().sorted(Comparator.comparing(value -> value.id().toString()))
                    .map(MybatisMediaPublicationManifestRepository::canonicalIdentity).toList());
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
            return new ResolvedMediaPublicationManifest(digest, identities);
        } catch (MediaPublicationPinException failure) { throw failure; }
        catch (Exception failure) { throw error(MediaPublicationPinException.Reason.UNAVAILABLE); }
    }

    private static void collect(Set<MediaObjectKey> keys, MediaLinkScan scan) {
        if (scan.failures().contains(MediaLinkScan.Failure.LIMIT_EXCEEDED)) throw error(MediaPublicationPinException.Reason.LIMIT_EXCEEDED);
        if (!scan.complete()) throw error(MediaPublicationPinException.Reason.CONFLICT);
        keys.addAll(scan.objectKeys());
        if (keys.size() > 10000) throw error(MediaPublicationPinException.Reason.LIMIT_EXCEEDED);
    }

    private static Map<String, String> canonicalIdentity(MediaPublicationResourceIdentity identity) {
        var row = new LinkedHashMap<String, String>();
        row.put("id", identity.id().toString()); row.put("provider", identity.provider());
        row.put("objectKey", identity.objectKey().value()); row.put("kind", identity.kind().name());
        return row;
    }

    private static MediaPublicationPinException error(MediaPublicationPinException.Reason reason) {
        return new MediaPublicationPinException(reason);
    }
}
