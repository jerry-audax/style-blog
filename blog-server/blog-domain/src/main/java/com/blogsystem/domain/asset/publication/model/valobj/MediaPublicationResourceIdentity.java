package com.blogsystem.domain.asset.publication.model.valobj;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.publication.model.MediaPublicationPinException;
import java.util.UUID;

public record MediaPublicationResourceIdentity(UUID id, String provider, MediaObjectKey objectKey, MediaKind kind) {
    public MediaPublicationResourceIdentity {
        if (id == null || provider == null || provider.isBlank() || provider.length() > 32
                || provider.chars().anyMatch(Character::isISOControl) || objectKey == null || kind == null)
            throw new MediaPublicationPinException(MediaPublicationPinException.Reason.INVALID_REQUEST);
    }
}
