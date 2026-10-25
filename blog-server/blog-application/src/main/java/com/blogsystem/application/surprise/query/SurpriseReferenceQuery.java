package com.blogsystem.application.surprise.query;

import com.blogsystem.domain.asset.inventory.model.query.MediaReference;

import java.util.List;

public interface SurpriseReferenceQuery {
    List<MediaReference> listMediaReferences();
}
