package com.blogsystem.domain.asset.inventory.gateway;

import com.blogsystem.domain.asset.inventory.model.query.MediaReference;

import java.util.List;

/** Read-only source of business references used by a media dry-run. */
public interface MediaReferenceSource {
    List<MediaReference> list();
}
