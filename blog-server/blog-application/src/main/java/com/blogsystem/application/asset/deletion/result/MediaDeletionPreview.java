package com.blogsystem.application.asset.deletion.result;

import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionTarget;
import com.blogsystem.domain.asset.deletion.policy.MediaDeletionPolicy.Decision;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Safe use-case projection; deliberately excludes storage locations and ownership notes. */
public record MediaDeletionPreview(
        MediaDeletionTarget target,
        long expectedVersion,
        List<Candidate> candidates,
        boolean referenceCoverageComplete,
        String environmentId) {
    public MediaDeletionPreview {
        candidates = List.copyOf(candidates);
    }

    public record Candidate(
            UUID assetId,
            MediaKind kind,
            long assetVersion,
            MediaDeletionOwnership.Scope deletionScope,
            String exclusiveEnvironment,
            Decision decision,
            MediaReferenceEvidence referenceEvidence) {
        public Candidate {
            Objects.requireNonNull(referenceEvidence, "引用证据不能为空");
        }
    }
}
