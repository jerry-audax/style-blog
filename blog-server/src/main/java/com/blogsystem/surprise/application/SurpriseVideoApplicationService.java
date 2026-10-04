package com.blogsystem.surprise.application;

import com.blogsystem.surprise.domain.model.*;
import com.blogsystem.surprise.domain.repository.SurpriseVideoMediaRepository;
import com.blogsystem.surprise.domain.repository.SurpriseVideoRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class SurpriseVideoApplicationService {
    private final SurpriseVideoRepository videos;
    private final SurpriseVideoMediaRepository media;

    public SurpriseVideoApplicationService(SurpriseVideoRepository videos, SurpriseVideoMediaRepository media) {
        this.videos = videos;
        this.media = media;
    }

    public List<VideoView> adminList() {
        return videos.findAll().stream().sorted(Comparator.comparingInt(SurpriseVideo::sortOrder).thenComparing(SurpriseVideo::id))
                .map(VideoView::from).toList();
    }

    public VideoView randomPublic() {
        List<SurpriseVideo> enabled = videos.findAll().stream().filter(SurpriseVideo::enabled).toList();
        if (enabled.isEmpty()) throw new IllegalStateException("暂时没有可播放的惊喜视频");
        return VideoView.from(enabled.get(ThreadLocalRandom.current().nextInt(enabled.size())));
    }

    public VideoView create(String title, boolean enabled, int sortOrder, SurpriseVideoUpload upload) {
        SurpriseVideoMedia stored = media.upload(upload);
        return VideoView.from(videos.save(new SurpriseVideo(UUID.randomUUID().toString(), normalizeTitle(title), stored.url(), stored.path(), stored.mediaType(), stored.size(), enabled, sortOrder)));
    }

    public VideoView update(String id, String title, Boolean enabled, Integer sortOrder) {
        SurpriseVideo existing = videos.findById(id).orElseThrow(() -> new IllegalArgumentException("视频不存在"));
        return VideoView.from(videos.save(existing.withMetadata(normalizeTitle(title == null ? existing.title() : title), enabled == null ? existing.enabled() : enabled, sortOrder == null ? existing.sortOrder() : sortOrder)));
    }

    public void delete(String id) {
        SurpriseVideo existing = videos.findById(id).orElseThrow(() -> new IllegalArgumentException("视频不存在"));
        media.delete(existing.path());
        videos.delete(id);
    }

    private static String normalizeTitle(String value) {
        if (value == null || value.isBlank()) return "惊喜视频";
        return value.trim().length() > 120 ? value.trim().substring(0, 120) : value.trim();
    }

    public record VideoView(String id, String title, String url, String path, String mediaType, long size, boolean enabled, int sortOrder) {
        static VideoView from(SurpriseVideo value) { return new VideoView(value.id(), value.title(), value.url(), value.path(), value.mediaType(), value.size(), value.enabled(), value.sortOrder()); }
    }
}
