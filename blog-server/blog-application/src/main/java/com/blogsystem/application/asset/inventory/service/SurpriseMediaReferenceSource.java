package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.surprise.query.SurpriseReferenceQuery;
import com.blogsystem.domain.asset.inventory.gateway.MediaReferenceSource;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;

import java.util.List;

public final class SurpriseMediaReferenceSource implements MediaReferenceSource {
    private final SurpriseReferenceQuery query;

    public SurpriseMediaReferenceSource(SurpriseReferenceQuery query) {
        this.query = query;
    }

    @Override
    public List<MediaReference> list() {
        try {
            return List.copyOf(query.listMediaReferences());
        } catch (RuntimeException failure) {
            throw new MediaInventoryException("数据库视频引用暂不可用");
        }
    }
}
