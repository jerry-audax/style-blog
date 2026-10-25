package com.blogsystem.infrastructure.adapter.repository.asset;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationPinRepository;
import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceRepository;
import com.blogsystem.domain.asset.reference.model.MediaReferenceQueryException;
import com.blogsystem.domain.asset.reference.model.gateway.MediaReferenceLinkGateway;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence.Source;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence.Usage;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaReferenceMapper;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** Live source reads, not an inventory cache or permission to physically delete a resource. */
public final class MybatisMediaReferenceRepository implements MediaReferenceRepository {
    private final MediaReferenceMapper mapper;
    private final MediaReferenceLinkGateway links;
    private final MediaPublicationPinRepository pins;

    public MybatisMediaReferenceRepository(MediaReferenceMapper mapper, MediaReferenceLinkGateway links, MediaPublicationPinRepository pins) {
        this.mapper = mapper; this.links = links; this.pins = pins;
    }

    @Override public MediaReferenceEvidence inspect(MediaAsset asset) {
        if (asset == null) throw new MediaReferenceQueryException(MediaReferenceQueryException.Reason.INVALID_REQUEST);
        Evidence evidence = new Evidence(asset);
        read(Source.MUSIC, evidence, () -> music(evidence));
        read(Source.SURPRISE, evidence, () -> mapper.selectVideoBindings(asset.id()).forEach(row -> evidence.add(Source.SURPRISE, row.businessId(), "惊喜视频")));
        read(Source.ARTICLE, evidence, () -> articles(evidence));
        read(Source.OWNER, evidence, () -> owners(evidence));
        read(Source.PUBLICATION, evidence, () -> {
            // HELD rows prove positive usages, not that every live/rollback release is registered.
            evidence.failed.add(Source.PUBLICATION);
            for (var pin : pins.findHolding(asset.id())) {
                var key = pin.key();
                evidence.add(Source.PUBLICATION, key.environmentId() + "/" + key.publisherId() + "/" + key.releaseId(), "发布版本");
            }
        });
        return new MediaReferenceEvidence(EnumSet.allOf(Source.class), evidence.successful,
                evidence.usages.stream().sorted(Comparator.comparing(Usage::source).thenComparing(Usage::businessId).thenComparing(Usage::label)).toList(), evidence.failed);
    }

    private void music(Evidence evidence) {
        for (var row : mapper.selectMusicBindings(evidence.asset.id())) {
            String label = switch (row.slot()) {
                case "AUDIO" -> "歌曲音频";
                case "COVER" -> "歌曲封面";
                case "LYRICS" -> "歌曲歌词";
                default -> null;
            };
            evidence.add(Source.MUSIC, row.businessId(), label);
        }
        if (!evidence.supportsUrls(Source.MUSIC)) return;
        for (var row : mapper.selectMusicExternalCovers()) {
            evidence.match(Source.MUSIC, row.businessId(), "歌曲封面", links.parseLink(row.resourceUrl()));
        }
    }

    private void articles(Evidence evidence) {
        if (!evidence.supportsUrls(Source.ARTICLE)) return;
        for (var row : mapper.selectArticleResources()) {
            String id = row.id() == null ? null : row.id().toString();
            evidence.match(Source.ARTICLE, id, "文章封面", links.parseLink(row.coverUrl()));
            evidence.match(Source.ARTICLE, id, "文章正文", links.parseContent(row.contentMd()));
            evidence.match(Source.ARTICLE, id, "文章正文", links.parseHtmlContent(row.contentHtml()));
        }
    }

    private void owners(Evidence evidence) {
        if (!evidence.supportsUrls(Source.OWNER)) return;
        for (var row : mapper.selectOwnerAvatars()) {
            evidence.match(Source.OWNER, row.id() == null ? null : row.id().toString(), "账号头像", links.parseLink(row.avatar()));
        }
    }

    private static void read(Source source, Evidence evidence, Runnable query) {
        try {
            query.run();
            if (!evidence.failed.contains(source)) evidence.successful.add(source);
        } catch (DataAccessException | PersistenceException | MediaPublicationPinException failure) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                // A caught SQL exception does not repair PostgreSQL's aborted transaction.
                throw new MediaReferenceQueryException(MediaReferenceQueryException.Reason.UNAVAILABLE);
            }
            evidence.failed.add(source);
        }
    }

    private static final class Evidence {
        private final MediaAsset asset;
        private final Set<Source> successful = EnumSet.noneOf(Source.class);
        private final Set<Source> failed = EnumSet.noneOf(Source.class);
        private final Set<Usage> usages = new HashSet<>();

        private Evidence(MediaAsset asset) { this.asset = asset; }
        private void add(Source source, String id, String label) {
            if (id == null || id.isBlank() || label == null) { failed.add(source); return; }
            usages.add(new Usage(source, id, label));
        }
        private void match(Source source, String id, String label, MediaLinkScan scan) {
            if (!scan.complete()) failed.add(source);
            if (scan.objectKeys().contains(asset.objectKey())) add(source, id, label);
        }
        private boolean supportsUrls(Source source) {
            if ("cloudflare_imgbed".equals(asset.provider())) return true;
            failed.add(source); return false;
        }
    }
}
