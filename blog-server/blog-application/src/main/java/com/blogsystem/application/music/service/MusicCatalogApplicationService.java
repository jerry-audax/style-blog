package com.blogsystem.application.music.service;

import com.blogsystem.application.music.port.MusicCatalogUseCase;
import com.blogsystem.domain.music.gateway.HostedAudioGateway;
import com.blogsystem.domain.music.gateway.MusicMediaGateway;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.model.HostedAudio;
import com.blogsystem.domain.music.model.MusicException;
import com.blogsystem.domain.music.model.MusicMedia;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicMediaUpload;
import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.domain.music.model.MusicTrack;
import com.blogsystem.domain.music.policy.PlaylistSongMatcher;
import com.blogsystem.domain.music.adapter.repository.MusicCatalogRepository;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Blog-owned music catalog. NetEase is only an optional metadata importer;
 * the catalog and hosted media are the public source of truth after import.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "media.catalog.storage",
        havingValue = "legacy-json",
        matchIfMissing = true)
public class MusicCatalogApplicationService implements MusicCatalogUseCase {
    private final HostedAudioGateway audio;
    private final MusicCatalogRepository catalog;
    private final MusicMediaGateway media;
    private final PlaylistSnapshotRepository snapshots;
    private final SongArtworkGateway artwork;
    private final MusicPolicy policy;

    public MusicCatalogApplicationService(
            HostedAudioGateway audio,
            MusicCatalogRepository catalog,
            MusicMediaGateway media,
            PlaylistSnapshotRepository snapshots,
            SongArtworkGateway artwork,
            MusicPolicy policy) {
        this.audio = audio;
        this.catalog = catalog;
        this.media = media;
        this.snapshots = snapshots;
        this.artwork = artwork;
        this.policy = policy;
    }

    public record MediaView(String path, String url, String mediaType, Long size) {
        public MediaView(String path, String url, String mediaType, long size) {
            this(path, url, mediaType, Long.valueOf(size));
        }
    }

    public record TrackView(
            String id,
            String title,
            String artist,
            String sourceId,
            boolean enabled,
            int sortOrder,
            MediaView audio,
            MediaView cover,
            MediaView lyrics,
            Long version) {
        public TrackView(
                String id,
                String title,
                String artist,
                String sourceId,
                boolean enabled,
                int sortOrder,
                MediaView audio,
                MediaView cover,
                MediaView lyrics) {
            this(id, title, artist, sourceId, enabled, sortOrder, audio, cover, lyrics, null);
        }
    }

    public record PublicSong(
            String id, String name, String artist, String cover, String url, String lyrics) {}

    public record PublicPlaylist(
            String source, String name, Instant updatedAt, List<PublicSong> songs) {}

    public List<TrackView> catalog() {
        var currentAudio = audio.findPublished();
        var tracks = reconcile(catalog.find(), currentAudio);
        var metadata = metadataByTitle();
        var covers = missingCovers(tracks, metadata);
        return tracks.stream()
                .sorted(
                        Comparator.comparingInt(MusicTrack::sortOrder)
                                .thenComparing(MusicTrack::title, String.CASE_INSENSITIVE_ORDER))
                .map(track -> view(track, currentAudio, metadata, covers))
                .toList();
    }

    public PublicPlaylist publicPlaylist() {
        var currentAudio = audio.findPublished();
        var byPath =
                currentAudio.stream()
                        .collect(Collectors.toMap(HostedAudio::id, a -> a, (left, right) -> left));
        var metadata = metadataByTitle();
        var tracks = reconcile(catalog.find(), currentAudio);
        var covers = missingCovers(tracks, metadata);
        var songs =
                tracks.stream()
                        .filter(MusicTrack::enabled)
                        .map(track -> publicSong(track, byPath, metadata, covers))
                        .filter(Objects::nonNull)
                        .toList();
        return new PublicPlaylist("imgbed", "博客音乐", Instant.now(), songs);
    }

    public TrackView saveMetadata(
            String id,
            String title,
            String artist,
            String sourceId,
            boolean enabled,
            int sortOrder) {
        List<MusicTrack> tracks =
                catalog.find()
                        .map(ArrayList::new)
                        .orElseGet(
                                () ->
                                        new ArrayList<>(
                                                reconcile(
                                                        Optional.empty(), audio.findPublished())));
        String actualId =
                id == null || id.isBlank() ? UUID.randomUUID().toString().replace("-", "") : id;
        int index = -1;
        for (int i = 0; i < tracks.size(); i++)
            if (tracks.get(i).id().equals(actualId)) {
                index = i;
                break;
            }
        MusicTrack next;
        if (index >= 0) {
            MusicTrack current = tracks.get(index);
            next = current.withMetadata(title, artist, sourceId, enabled, sortOrder);
            tracks.set(index, next);
        } else {
            next =
                    new MusicTrack(
                            actualId, title, artist, sourceId, "", "", "", enabled, sortOrder);
            tracks.add(next);
        }
        catalog.save(tracks);
        return catalog().stream()
                .filter(track -> track.id().equals(actualId))
                .findFirst()
                .orElseThrow();
    }

    @Override
    public TrackView createMetadata(
            String id,
            String title,
            String artist,
            String sourceId,
            boolean enabled,
            int sortOrder) {
        return saveMetadata(id, title, artist, sourceId, enabled, sortOrder);
    }

    @Override
    public TrackView updateMetadata(
            String id,
            String title,
            String artist,
            String sourceId,
            boolean enabled,
            int sortOrder,
            Long expectedVersion) {
        return saveMetadata(id, title, artist, sourceId, enabled, sortOrder);
    }

    public TrackView upload(String id, MusicMediaUpload upload) {
        MusicTrack current = requireTrack(id);
        MusicMedia stored = media.upload(upload);
        MusicTrack next = current.withMedia(upload.kind(), stored.path());
        List<MusicTrack> tracks =
                catalog.find()
                        .map(ArrayList::new)
                        .orElseThrow(() -> new MusicException(MusicException.Reason.UNAVAILABLE));
        tracks.replaceAll(track -> track.id().equals(id) ? next : track);
        catalog.save(tracks);
        audio.invalidate();
        if (oldPath(current, upload.kind()) != null
                && !oldPath(current, upload.kind()).isBlank()
                && !oldPath(current, upload.kind()).equals(stored.path())) {
            try {
                media.delete(oldPath(current, upload.kind()));
            } catch (RuntimeException ignored) {
                /* active new media remains authoritative */
            }
        }
        return catalog().stream().filter(track -> track.id().equals(id)).findFirst().orElseThrow();
    }

    public TrackView deleteMedia(String id, MusicMediaKind kind) {
        MusicTrack current = requireTrack(id);
        String path = oldPath(current, kind);
        if (path == null || path.isBlank())
            return catalog().stream()
                    .filter(track -> track.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        media.delete(path);
        List<MusicTrack> tracks =
                catalog.find()
                        .map(ArrayList::new)
                        .orElseThrow(() -> new MusicException(MusicException.Reason.UNAVAILABLE));
        tracks.replaceAll(track -> track.id().equals(id) ? track.withMedia(kind, "") : track);
        catalog.save(tracks);
        audio.invalidate();
        return catalog().stream().filter(track -> track.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Removes a blog-owned song as one management operation.  The remote media
     * objects are deleted first; the catalog record is removed only after all
     * associated objects (audio, cover and lyrics) have been handled.  If the
     * provider fails, the catalog remains available for a retry and the public
     * projection is refreshed so it never serves a stale cached object list.
     */
    public void deleteTrack(String id) {
        MusicTrack current = requireTrack(id);
        try {
            for (String path :
                    List.of(current.audioPath(), current.coverPath(), current.lyricsPath())) {
                if (path != null && !path.isBlank()) media.delete(path);
            }
            List<MusicTrack> tracks =
                    catalog.find()
                            .map(ArrayList::new)
                            .orElseThrow(
                                    () -> new MusicException(MusicException.Reason.UNAVAILABLE));
            tracks.removeIf(track -> track.id().equals(id));
            catalog.save(tracks);
        } finally {
            audio.invalidate();
        }
    }

    private MusicTrack requireTrack(String id) {
        if (id == null || id.isBlank())
            throw new MusicException(MusicException.Reason.TRACK_NOT_FOUND);
        return catalog
                .find()
                .orElseGet(() -> reconcile(Optional.empty(), audio.findPublished()))
                .stream()
                .filter(track -> track.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new MusicException(MusicException.Reason.TRACK_NOT_FOUND));
    }

    private List<MusicTrack> reconcile(
            Optional<List<MusicTrack>> stored, List<HostedAudio> currentAudio) {
        List<MusicTrack> tracks = new ArrayList<>(stored.orElseGet(() -> bootstrap(currentAudio)));
        Set<String> paths =
                tracks.stream()
                        .map(MusicTrack::audioPath)
                        .filter(path -> !path.isBlank())
                        .collect(Collectors.toSet());
        Map<String, MusicPlaylist.Song> imported = metadataByTitle();
        boolean changed = false;
        int order = tracks.stream().mapToInt(MusicTrack::sortOrder).max().orElse(-1) + 1;
        for (HostedAudio item : currentAudio) {
            if (paths.contains(item.id())) continue;
            MusicPlaylist.Song song = imported.get(PlaylistSongMatcher.title(item.name()));
            tracks.add(
                    new MusicTrack(
                            trackId(item.id()),
                            item.name(),
                            song == null ? "" : song.artist(),
                            song == null ? "" : song.id(),
                            item.id(),
                            "",
                            "",
                            true,
                            order++));
            changed = true;
        }
        if (stored.isEmpty() || changed) catalog.save(tracks);
        return tracks;
    }

    private List<MusicTrack> bootstrap(List<HostedAudio> currentAudio) {
        Map<String, MusicPlaylist.Song> imported = metadataByTitle();
        List<MusicTrack> tracks = new ArrayList<>();
        int order = 0;
        for (HostedAudio item : currentAudio) {
            MusicPlaylist.Song song = imported.get(PlaylistSongMatcher.title(item.name()));
            tracks.add(
                    new MusicTrack(
                            trackId(item.id()),
                            item.name(),
                            song == null ? "" : song.artist(),
                            song == null ? "" : song.id(),
                            item.id(),
                            "",
                            "",
                            true,
                            order++));
        }
        return tracks;
    }

    private TrackView view(
            MusicTrack track,
            List<HostedAudio> currentAudio,
            Map<String, MusicPlaylist.Song> metadata,
            Map<String, String> covers) {
        Map<String, HostedAudio> byPath =
                currentAudio.stream()
                        .collect(Collectors.toMap(HostedAudio::id, a -> a, (left, right) -> left));
        HostedAudio item = byPath.get(track.audioPath());
        MediaView audioView =
                item == null && !track.audioPath().isBlank()
                        ? media(track.audioPath(), "", 0)
                        : item == null
                                ? null
                                : media(item.id(), item.mediaType(), item.size(), item.url());
        String fallback =
                sourceSong(metadata, track.sourceId()).map(MusicPlaylist.Song::cover).orElse("");
        if (fallback.isBlank() && !track.sourceId().isBlank())
            fallback = covers.getOrDefault(track.sourceId(), "");
        MediaView cover =
                track.coverPath().isBlank()
                        ? (fallback.isBlank() ? null : new MediaView("", fallback, "image/jpeg", 0))
                        : media(track.coverPath(), "", 0);
        MediaView lyrics =
                track.lyricsPath().isBlank() ? null : media(track.lyricsPath(), "text/plain", 0);
        return new TrackView(
                track.id(),
                track.title(),
                track.artist(),
                track.sourceId(),
                track.enabled(),
                track.sortOrder(),
                audioView,
                cover,
                lyrics);
    }

    private PublicSong publicSong(
            MusicTrack track,
            Map<String, HostedAudio> byPath,
            Map<String, MusicPlaylist.Song> metadata,
            Map<String, String> covers) {
        HostedAudio audio = byPath.get(track.audioPath());
        if (audio == null) return null;
        String cover = track.coverPath().isBlank() ? "" : media.publicUrl(track.coverPath());
        if (cover.isBlank() && !track.sourceId().isBlank()) {
            MusicPlaylist.Song song = sourceSong(metadata, track.sourceId()).orElse(null);
            cover = song == null ? "" : song.cover();
            if (cover.isBlank()) cover = covers.getOrDefault(track.sourceId(), "");
        }
        String lyrics = track.lyricsPath().isBlank() ? "" : media.publicUrl(track.lyricsPath());
        return new PublicSong(
                track.id(), track.title(), track.artist(), cover, audio.url(), lyrics);
    }

    private Map<String, MusicPlaylist.Song> metadataByTitle() {
        try {
            return snapshots
                    .find()
                    .filter(p -> p.playlistId().equals(policy.playlistId()))
                    .map(p -> PlaylistSongMatcher.uniqueTitles(p.songs()))
                    .orElse(Map.of());
        } catch (MusicException ignored) {
            return Map.of();
        }
    }

    private Map<String, String> missingCovers(
            List<MusicTrack> tracks, Map<String, MusicPlaylist.Song> metadata) {
        Set<String> ids =
                tracks.stream()
                        .filter(track -> !track.sourceId().isBlank() && track.coverPath().isBlank())
                        .map(MusicTrack::sourceId)
                        .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : artwork.findCovers(ids);
    }

    private static Optional<MusicPlaylist.Song> sourceSong(
            Map<String, MusicPlaylist.Song> metadata, String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return Optional.empty();
        return metadata.values().stream().filter(song -> sourceId.equals(song.id())).findFirst();
    }

    private static String trackId(String path) {
        String encoded =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(path.getBytes(StandardCharsets.UTF_8));
        return encoded.substring(0, Math.min(60, encoded.length()));
    }

    private MediaView media(String path, String type, long size) {
        return new MediaView(path, media.publicUrl(path), type, size);
    }

    private MediaView media(String path, String type, long size, String url) {
        return new MediaView(path, url, type, size);
    }

    private static String oldPath(MusicTrack track, MusicMediaKind kind) {
        return switch (kind) {
            case AUDIO -> track.audioPath();
            case COVER -> track.coverPath();
            case LYRICS -> track.lyricsPath();
        };
    }
}
