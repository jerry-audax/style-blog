package com.blogsystem.application.asset.publication.service;

import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationManifestRepository;
import com.blogsystem.domain.asset.publication.model.entity.MediaPublicationPin;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationManifestInput;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import java.util.Objects;

public final class MediaPublicationMachineApplicationService {
    private final MediaPublicationPinApplicationService pins;
    private final MediaPublicationManifestRepository manifests;
    private final String environmentId;
    private final String publisherId;

    public MediaPublicationMachineApplicationService(MediaPublicationPinApplicationService pins,
            MediaPublicationManifestRepository manifests, String environmentId, String publisherId) {
        new MediaPublicationPinKey(environmentId, publisherId, "configuration-check");
        this.pins = Objects.requireNonNull(pins); this.manifests = Objects.requireNonNull(manifests);
        this.environmentId = environmentId; this.publisherId = publisherId;
    }

    public MediaPublicationPin prepare(String releaseId, MediaPublicationManifestInput input) {
        var key = key(releaseId);
        return pins.prepareVerified(key, manifests.resolve(input));
    }

    public MediaPublicationPin get(String releaseId) { return pins.get(key(releaseId)); }

    public MediaPublicationPin release(String releaseId, long expectedVersion, String manifestDigest) {
        return pins.release(key(releaseId), expectedVersion, manifestDigest);
    }

    public void verify(String releaseId, String manifestDigest, MediaPublicationManifestInput output) {
        var key = key(releaseId);
        pins.verifyHeld(key, manifestDigest, manifests.resolve(output));
    }

    private MediaPublicationPinKey key(String releaseId) {
        return new MediaPublicationPinKey(environmentId, publisherId, releaseId);
    }
}
