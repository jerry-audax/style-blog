package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.music.query.MusicReferenceQuery;
import com.blogsystem.domain.asset.inventory.gateway.MediaReferenceSource;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;

import java.util.List;

public final class MusicMediaReferenceSource implements MediaReferenceSource {
    private final MusicReferenceQuery query;

    public MusicMediaReferenceSource(MusicReferenceQuery query) {
        this.query = query;
    }

    @Override
    public List<MediaReference> list() {
        try {
            return List.copyOf(query.listMediaReferences());
        } catch (RuntimeException failure) {
            throw new MediaInventoryException("数据库音乐引用暂不可用");
        }
    }
}
