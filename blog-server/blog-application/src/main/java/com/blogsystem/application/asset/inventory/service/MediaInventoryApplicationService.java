package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.domain.asset.inventory.gateway.MediaInventoryGateway;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryPreview;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Coordinates a read-only MEDIA-02 inventory preview. */
@Service
public class MediaInventoryApplicationService {
    private final MediaInventoryGateway repository;

    public MediaInventoryApplicationService(MediaInventoryGateway repository) {
        this.repository = Objects.requireNonNull(repository, "盘点仓储不能为空");
    }

    public MediaInventoryPreview preview(Set<MediaKind> requestedKinds) {
        if (requestedKinds == null || requestedKinds.isEmpty()) {
            throw new IllegalArgumentException("至少指定一种盘点资源类型");
        }
        EnumMap<MediaKind, java.util.List<MediaInventoryObject>> result =
                new EnumMap<>(MediaKind.class);
        Set<String> locations = new HashSet<>();
        for (MediaKind kind : requestedKinds) {
            if (kind == null) throw new IllegalArgumentException("盘点资源类型不能为空");
            var supplied = repository.list(kind);
            if (supplied == null) {
                throw new MediaInventoryException("盘点仓储返回空资源列表");
            }
            for (MediaInventoryObject object : supplied) {
                if (object == null) {
                    throw new MediaInventoryException("盘点仓储返回空资源明细");
                }
            }
            var objects = java.util.List.copyOf(supplied);
            for (MediaInventoryObject object : objects) {
                if (object.kind() != kind || !locations.add(object.objectKey().value())) {
                    throw new MediaInventoryException("盘点结果包含重复或类型不一致的资源");
                }
            }
            result.put(kind, objects);
        }
        return new MediaInventoryPreview(result);
    }
}
