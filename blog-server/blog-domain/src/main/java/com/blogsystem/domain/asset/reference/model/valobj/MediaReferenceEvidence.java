package com.blogsystem.domain.asset.reference.model.valobj;

import java.util.List;
import java.util.Set;

/** Positive usages survive partial failures; an empty result alone never proves completeness. */
public record MediaReferenceEvidence(Set<Source> requiredSources, Set<Source> successfulSources,
        List<Usage> usages, Set<Source> failedSources) {
    public enum Source { MUSIC, SURPRISE, ARTICLE, OWNER, PUBLICATION }

    public record Usage(Source source, String businessId, String label) {
        public Usage {
            if (source == null || businessId == null || businessId.isBlank() || label == null || label.isBlank()) {
                throw new IllegalArgumentException("引用用途不完整");
            }
        }
    }

    public MediaReferenceEvidence {
        if (requiredSources == null || successfulSources == null || usages == null || failedSources == null
                || requiredSources.stream().anyMatch(s -> s == null)
                || successfulSources.stream().anyMatch(s -> s == null)
                || failedSources.stream().anyMatch(s -> s == null) || usages.stream().anyMatch(u -> u == null)) {
            throw new IllegalArgumentException("引用证据不完整");
        }
        requiredSources = Set.copyOf(requiredSources);
        successfulSources = Set.copyOf(successfulSources);
        usages = List.copyOf(usages);
        failedSources = Set.copyOf(failedSources);
    }

    public boolean complete() { return failedSources.isEmpty() && successfulSources.containsAll(requiredSources); }
}
