package com.blogsystem.trigger.http.asset.dto;

import com.blogsystem.domain.asset.publication.model.entity.MediaPublicationPin;
import java.util.List;

public record MediaPublicationPinView(int protocolVersion, String environmentId, String publisherId,
        String releaseId, String manifestDigest, List<String> assetIds, String status, String version) {
    public static MediaPublicationPinView from(MediaPublicationPin pin) {
        return new MediaPublicationPinView(1, pin.key().environmentId(), pin.key().publisherId(), pin.key().releaseId(),
                pin.manifestDigest(), pin.assetIds().stream().map(Object::toString).sorted().toList(), pin.status().name(), Long.toString(pin.version()));
    }
}
