package com.blogsystem.domain.asset.inventory.gateway;

import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

import java.util.List;

/** Read-only provider inventory port for the MEDIA-02 dry-run. */
public interface MediaInventoryGateway {
    List<MediaInventoryObject> list(MediaKind kind);
}
