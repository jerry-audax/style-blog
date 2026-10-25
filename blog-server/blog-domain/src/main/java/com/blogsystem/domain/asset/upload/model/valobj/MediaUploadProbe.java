package com.blogsystem.domain.asset.upload.model.valobj;

import java.util.List;

public record MediaUploadProbe(boolean complete, List<MediaUploadCandidate> candidates) {
    public MediaUploadProbe {
        candidates = List.copyOf(candidates);
        if (candidates.size() > 2) throw new IllegalArgumentException("Probe must be bounded");
    }
}
