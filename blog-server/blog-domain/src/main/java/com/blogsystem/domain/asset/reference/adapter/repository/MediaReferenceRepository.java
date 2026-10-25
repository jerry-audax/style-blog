package com.blogsystem.domain.asset.reference.adapter.repository;

import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence;

/** Live persisted usages; an inventory or a preview is not a deletion authorization snapshot. */
public interface MediaReferenceRepository {
    MediaReferenceEvidence inspect(MediaAsset asset);
}
