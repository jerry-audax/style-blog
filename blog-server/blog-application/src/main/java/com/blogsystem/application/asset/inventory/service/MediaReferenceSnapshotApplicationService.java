package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.domain.asset.inventory.gateway.MediaReferenceSource;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Collects read-only business references for a reconciliation snapshot. */
@Service
public final class MediaReferenceSnapshotApplicationService {
    private final List<MediaReferenceSource> sources;

    public MediaReferenceSnapshotApplicationService(List<MediaReferenceSource> sources) {
        Objects.requireNonNull(sources, "引用来源不能为空");
        this.sources =
                sources.stream()
                        .map(source -> Objects.requireNonNull(source, "引用来源明细不能为空"))
                        .toList();
    }

    public List<MediaReference> collect() {
        List<MediaReference> result = new ArrayList<>();
        for (MediaReferenceSource source : sources) {
            List<MediaReference> references = source.list();
            if (references == null) {
                throw new IllegalStateException("引用来源返回空列表");
            }
            for (MediaReference reference : references) {
                if (reference == null) {
                    throw new IllegalStateException("引用来源返回空明细");
                }
            }
            result.addAll(references);
        }
        return List.copyOf(result);
    }
}
