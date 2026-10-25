package com.blogsystem.domain.asset.publication.model.valobj;

import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public record ResolvedMediaPublicationManifest(String manifestDigest, Set<MediaPublicationResourceIdentity> resources) {
    public ResolvedMediaPublicationManifest {
        if (manifestDigest == null || !manifestDigest.matches("[0-9a-f]{64}")
                || resources == null) throw invalid();
        if (resources.size() > 10000) throw new MediaPublicationPinException(MediaPublicationPinException.Reason.LIMIT_EXCEEDED);
        var ids = new HashSet<UUID>();
        for (var identity : resources)
            if (identity == null || !ids.add(identity.id())) throw invalid();
        resources = Set.copyOf(resources);
    }

    private static MediaPublicationPinException invalid() {
        return new MediaPublicationPinException(MediaPublicationPinException.Reason.INVALID_REQUEST);
    }
}
