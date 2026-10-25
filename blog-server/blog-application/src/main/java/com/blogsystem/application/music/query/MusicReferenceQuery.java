package com.blogsystem.application.music.query;

import com.blogsystem.domain.asset.inventory.model.query.MediaReference;

import java.util.List;

public interface MusicReferenceQuery {
    List<MediaReference> listMediaReferences();
}
