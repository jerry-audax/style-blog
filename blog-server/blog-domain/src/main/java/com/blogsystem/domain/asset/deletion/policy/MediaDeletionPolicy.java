package com.blogsystem.domain.asset.deletion.policy;

import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.ItemStatus;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.Reason;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionAssetSnapshot;
import com.blogsystem.domain.asset.deletion.model.valobj.MediaDeletionOwnership;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;

import java.util.Objects;

/** Pure eligibility only; execution additionally requires provider/configuration guards. */
public final class MediaDeletionPolicy {
    public record Evidence(
            String environmentId,
            boolean referenceCoverageComplete,
            boolean hasReferences,
            boolean writeGuardsComplete) {}

    public record Decision(ItemStatus status, Reason reason) {}

    public Decision evaluate(MediaDeletionAssetSnapshot snapshot, Evidence evidence) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(evidence);
        if (snapshot.asset().status() != MediaAssetStatus.ACTIVE)
            return retained(Reason.ASSET_NOT_ACTIVE);
        var ownership = snapshot.ownership();
        if (ownership.scope() == MediaDeletionOwnership.Scope.UNVERIFIED)
            return retained(Reason.OWNERSHIP_UNVERIFIED);
        if (ownership.scope() == MediaDeletionOwnership.Scope.SHARED)
            return retained(Reason.SHARED);
        if (!ownership.exclusiveEnvironment().equals(evidence.environmentId()))
            return retained(Reason.ENVIRONMENT_MISMATCH);
        if (!evidence.referenceCoverageComplete())
            return retained(Reason.REFERENCE_COVERAGE_INCOMPLETE);
        if (evidence.hasReferences()) return retained(Reason.IN_USE);
        if (!evidence.writeGuardsComplete()) return retained(Reason.WRITE_GUARDS_INCOMPLETE);
        return new Decision(ItemStatus.PENDING, Reason.NONE);
    }

    private Decision retained(Reason reason) {
        return new Decision(ItemStatus.RETAINED, reason);
    }
}
