package com.blogsystem.infrastructure.filesystem.asset;

import com.blogsystem.domain.asset.inventory.gateway.MediaReferenceSource;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.surprise.model.SurpriseVideo;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoRepository;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Projects legacy surprise-video references without changing the video catalog. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "media.catalog.storage",
        havingValue = "legacy-json",
        matchIfMissing = true)
public final class SurpriseVideoMediaReferenceSource implements MediaReferenceSource {
    private final SurpriseVideoRepository videos;

    public SurpriseVideoMediaReferenceSource(SurpriseVideoRepository videos) {
        this.videos = videos;
    }

    @Override
    public List<MediaReference> list() {
        return project(videos.findAll());
    }

    public static List<MediaReference> project(List<SurpriseVideo> values) {
        if (values == null) {
            throw new MediaInventoryException("惊喜视频目录返回空列表");
        }
        List<MediaReference> references = new ArrayList<>();
        for (SurpriseVideo video : values) {
            if (video == null) {
                throw new MediaInventoryException("惊喜视频目录包含空视频");
            }
            try {
                references.add(
                        new MediaReference(
                                MediaKind.VIDEO,
                                new MediaObjectKey(video.path()),
                                "surprise_video",
                                video.id(),
                                video.title()));
            } catch (IllegalArgumentException error) {
                throw new MediaInventoryException("惊喜视频目录包含不安全的媒体路径", error);
            }
        }
        return List.copyOf(references);
    }
}
