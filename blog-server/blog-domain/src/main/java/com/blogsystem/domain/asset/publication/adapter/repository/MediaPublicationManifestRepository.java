package com.blogsystem.domain.asset.publication.adapter.repository;

import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationManifestInput;
import com.blogsystem.domain.asset.publication.model.valobj.ResolvedMediaPublicationManifest;

public interface MediaPublicationManifestRepository {
    ResolvedMediaPublicationManifest resolve(MediaPublicationManifestInput input);
}
