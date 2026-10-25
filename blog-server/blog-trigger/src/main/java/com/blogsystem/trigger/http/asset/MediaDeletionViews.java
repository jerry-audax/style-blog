package com.blogsystem.trigger.http.asset;

import com.blogsystem.application.asset.deletion.result.MediaDeletionPreview;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionTarget;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence;

import java.util.List;
import java.util.Comparator;
import java.util.UUID;

/** Explicit whitelist: never serialize entities, storage locations, or private audit notes. */
public final class MediaDeletionViews {
    private MediaDeletionViews() {}

    public record Target(String kind, String id, String slot) {}

    public record Usage(String source, String businessId, String label) {}

    public record Candidate(
            UUID assetId,
            String kind,
            String assetVersion,
            String deletionScope,
            String exclusiveEnvironment,
            String status,
            String reason,
            List<Usage> usages,
            String usageCount,
            List<String> sourceFailures) {}

    public record Preview(
            Target target,
            String expectedVersion,
            List<Candidate> candidates,
            boolean referenceCoverageComplete,
            String environmentId) {}

    public record Item(
            UUID id,
            UUID assetId,
            String kind,
            String assetVersion,
            String status,
            String reason) {}

    public record Operation(
            UUID operationId,
            Target target,
            String originalVersion,
            String committedAt,
            boolean localRemovalCommitted,
            boolean processingComplete,
            boolean allFilesDeleted,
            List<Item> items) {}

    public record Page(List<Operation> records, long total) {}

    public record Ownership(
            UUID assetId, String assetVersion, String deletionScope, String exclusiveEnvironment) {}

    static Target target(MediaDeletionTarget t) {
        return new Target(t.kind().name(), t.id(), t.slot() == null ? null : t.slot().name());
    }

    static Preview preview(MediaDeletionPreview p) {
        return new Preview(
                target(p.target()),
                Long.toString(p.expectedVersion()),
                p.candidates().stream()
                        .map(MediaDeletionViews::candidate)
                        .toList(),
                p.referenceCoverageComplete(),
                p.environmentId());
    }

    private static Candidate candidate(MediaDeletionPreview.Candidate candidate) {
        var evidence = candidate.referenceEvidence();
        var usages = evidence.usages().stream().distinct().sorted(Comparator
                .comparing(MediaReferenceEvidence.Usage::source)
                .thenComparing(MediaReferenceEvidence.Usage::businessId)
                .thenComparing(MediaReferenceEvidence.Usage::label)).toList();
        return new Candidate(candidate.assetId(), candidate.kind().name(), Long.toString(candidate.assetVersion()),
                candidate.deletionScope().name(), candidate.exclusiveEnvironment(), candidate.decision().status().name(),
                candidate.decision().reason().name(), usages.stream().limit(50)
                        .map(usage -> new Usage(usage.source().name(), usage.businessId(), usage.label())).toList(),
                Integer.toString(usages.size()), evidence.failedSources().stream().sorted().map(source -> source.name()).toList());
    }

    static Operation operation(MediaDeletionOperation op) {
        return new Operation(
                op.id(),
                target(op.target()),
                Long.toString(op.originalVersion()),
                op.committedAt().toString(),
                true,
                op.processingComplete(),
                op.allFilesDeleted(),
                op.items().stream()
                        .map(
                                i ->
                                        new Item(
                                                i.id(),
                                                i.assetId(),
                                                i.kind().name(),
                                                Long.toString(i.assetVersion()),
                                                i.resolvedStatus().name(),
                                                i.resolvedReason().name()))
                        .toList());
    }

    static Ownership ownership(MediaDeletionAssetSnapshot a) {
        return new Ownership(
                a.asset().id(),
                Long.toString(a.asset().version()),
                a.ownership().scope().name(),
                a.ownership().exclusiveEnvironment());
    }
}
