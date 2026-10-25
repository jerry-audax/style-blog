package com.blogsystem.infrastructure.mybatis.converter.surprise;

import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.domain.surprise.model.SurpriseVideoSnapshot;
import com.blogsystem.infrastructure.mybatis.record.surprise.SurpriseVideoRecord;

public final class SurpriseVideoConverter {
    private SurpriseVideoConverter() {}

    public static SurpriseVideoSnapshot toSnapshot(SurpriseVideoRecord row) {
        return new SurpriseVideoSnapshot(
                new PersistedSurpriseVideo(
                        row.id(), row.title(), row.videoAssetId(), row.enabled(), row.sortOrder()),
                row.version(),
                row.createdAt().toInstant(),
                row.updatedAt().toInstant());
    }
}
