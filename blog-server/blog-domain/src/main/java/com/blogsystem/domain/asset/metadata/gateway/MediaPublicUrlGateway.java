package com.blogsystem.domain.asset.metadata.gateway;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

/** Pure URL projection, with no provider read, credential, or file mutation. */
public interface MediaPublicUrlGateway {
    String publicUrl(String provider, MediaObjectKey key);
}
