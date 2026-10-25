package com.blogsystem.application.music.service;

import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.CONFLICT;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.INVALID_REQUEST;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.MISSING_VERSION;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.NOT_FOUND;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.OPERATION_NOT_READY;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.TOO_LARGE;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.UNAVAILABLE;

import com.blogsystem.application.asset.metadata.result.MediaAssetProjection;
import com.blogsystem.application.asset.metadata.service.MediaAssetQueryApplicationService;
import com.blogsystem.application.music.port.HostedMusicQueryUseCase;
import com.blogsystem.application.music.port.MusicCatalogUseCase;
import com.blogsystem.application.music.query.MusicReferenceQuery;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicMediaUpload;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.domain.music.model.MusicTrackMetadata;
import com.blogsystem.domain.music.model.MusicTrackSnapshot;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;

import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Database source of truth. GET cannot discover or import provider files. */
public final class PersistentMusicCatalogApplicationService
        implements MusicCatalogUseCase, HostedMusicQueryUseCase, MusicReferenceQuery {
    private final MusicTrackRepository tracks;
    private final MediaAssetQueryApplicationService assets;
    private final Clock clock;
    private final TransactionOperations transactions;

    public PersistentMusicCatalogApplicationService(
            MusicTrackRepository tracks,
            MediaAssetQueryApplicationService assets,
            PlaylistSnapshotRepository snapshots,
            SongArtworkGateway artwork,
            MusicPolicy policy,
            Clock clock,
            TransactionOperations transactions) {
        this.tracks = Objects.requireNonNull(tracks);
        this.assets = Objects.requireNonNull(assets);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public List<MusicCatalogApplicationService.TrackView> catalog() {
        return read(
                () -> {
                    var rows = tracks.findAdminList();
                    var prepared = prepare(rows.stream().map(MusicTrackSnapshot::track).toList());
                    return rows.stream().map(row -> view(row, prepared)).toList();
                });
    }

    @Override
    public HostedMusicApplicationService.Playlist playlist() {
        return read(
                () -> {
                    var rows = tracks.findPublicCandidates(2001);
                    if (rows.size() > 2000) throw new MusicCatalogException(TOO_LARGE);
                    var prepared = prepare(rows.stream().map(MusicTrackSnapshot::track).toList());
                    List<HostedMusicApplicationService.Song> songs = new ArrayList<>();
                    for (var row : rows) {
                        var track = row.track();
                        var audio = projection(prepared.media(), track.audioAssetId());
                        if (!eligible(audio, MediaKind.AUDIO) || !track.enabled()) continue;
                        var lyrics = projection(prepared.media(), track.lyricsAssetId());
                        songs.add(
                                new HostedMusicApplicationService.Song(
                                        track.id(),
                                        track.title(),
                                        track.artist(),
                                        coverUrl(track, prepared),
                                        audio.url(),
                                        audio.mimeType() == null ? "" : audio.mimeType(),
                                        audio.size() == null ? 0 : audio.size(),
                                        eligible(lyrics, MediaKind.LYRICS) ? lyrics.url() : ""));
                    }
                    return new HostedMusicApplicationService.Playlist(
                            "imgbed", "博客音乐", List.copyOf(songs));
                });
    }

    @Override
    public MusicCatalogApplicationService.TrackView createMetadata(
            String id,
            String title,
            String artist,
            String sourceId,
            boolean enabled,
            int sortOrder) {
        var metadata = metadata(title, artist, sourceId, enabled, sortOrder);
        String actualId =
                id == null || id.isBlank() ? UUID.randomUUID().toString().replace("-", "") : id;
        if (!actualId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new MusicCatalogException(INVALID_REQUEST);
        var candidate =
                new PersistedMusicTrack(
                        actualId,
                        metadata.title(),
                        metadata.artist(),
                        metadata.sourceId(),
                        null,
                        null,
                        null,
                        null,
                        enabled,
                        sortOrder);
        var prepared = read(() -> prepare(List.of(candidate)));
        var committed =
                read(
                        () ->
                                transactions.execute(
                                        status ->
                                                tracks.insertNewMusicTrack(
                                                        candidate, clock.instant())));
        if (committed == null) throw new MusicCatalogException(UNAVAILABLE);
        return view(committed, prepared);
    }

    @Override
    public MusicCatalogApplicationService.TrackView updateMetadata(
            String id,
            String title,
            String artist,
            String sourceId,
            boolean enabled,
            int sortOrder,
            Long expectedVersion) {
        requireVersion(expectedVersion);
        var metadata = metadata(title, artist, sourceId, enabled, sortOrder);
        var current =
                read(
                        () ->
                                tracks.findSnapshotById(id)
                                        .orElseThrow(() -> new MusicCatalogException(NOT_FOUND)));
        if (current.version() != expectedVersion) throw new MusicCatalogException(CONFLICT);
        var t = current.track();
        var candidate =
                new PersistedMusicTrack(
                        t.id(),
                        metadata.title(),
                        metadata.artist(),
                        metadata.sourceId(),
                        t.audioAssetId(),
                        t.coverAssetId(),
                        t.lyricsAssetId(),
                        t.externalCoverUrl(),
                        enabled,
                        sortOrder);
        var prepared = read(() -> prepare(List.of(candidate)));
        var committed =
                read(
                        () ->
                                transactions.execute(
                                        status ->
                                                tracks.updateMetadata(
                                                        id,
                                                        metadata,
                                                        expectedVersion,
                                                        clock.instant())));
        if (committed == null) throw new MusicCatalogException(UNAVAILABLE);
        // Nothing after commit queries storage, snapshots, or the provider.
        return view(committed, prepared);
    }

    @Override
    public MusicCatalogApplicationService.TrackView upload(String id, MusicMediaUpload upload) {
        throw new MusicCatalogException(OPERATION_NOT_READY);
    }

    @Override
    public MusicCatalogApplicationService.TrackView deleteMedia(String id, MusicMediaKind kind) {
        throw new MusicCatalogException(OPERATION_NOT_READY);
    }

    @Override
    public void deleteTrack(String id) {
        throw new MusicCatalogException(OPERATION_NOT_READY);
    }

    @Override
    public List<MediaReference> listMediaReferences() {
        return read(
                () -> {
                    var rows = tracks.findAdminList();
                    var media =
                            assets.findByIds(
                                    assetIds(
                                            rows.stream().map(MusicTrackSnapshot::track).toList()));
                    List<MediaReference> refs = new ArrayList<>();
                    for (var row : rows) {
                        var track = row.track();
                        addReference(
                                refs, media, track, track.audioAssetId(), MediaKind.AUDIO, "音频");
                        addReference(
                                refs, media, track, track.coverAssetId(), MediaKind.IMAGE, "封面");
                        addReference(
                                refs, media, track, track.lyricsAssetId(), MediaKind.LYRICS, "歌词");
                    }
                    return List.copyOf(refs);
                });
    }

    private Prepared prepare(List<PersistedMusicTrack> rows) {
        // PostgreSQL is authoritative; optional artwork acquisition is an explicit admin write.
        return new Prepared(assets.findByIds(assetIds(rows)));
    }

    private static Set<UUID> assetIds(List<PersistedMusicTrack> rows) {
        Set<UUID> ids = new HashSet<>();
        for (var row : rows) {
            if (row.audioAssetId() != null) ids.add(row.audioAssetId());
            if (row.coverAssetId() != null) ids.add(row.coverAssetId());
            if (row.lyricsAssetId() != null) ids.add(row.lyricsAssetId());
        }
        return Set.copyOf(ids);
    }

    private static MusicCatalogApplicationService.TrackView view(
            MusicTrackSnapshot row, Prepared prepared) {
        var t = row.track();
        var custom = projection(prepared.media(), t.coverAssetId());
        var cover = eligible(custom, MediaKind.IMAGE) ? mediaView(custom) : null;
        String fallback = cover == null ? coverUrl(t, prepared) : "";
        if (cover == null && !fallback.isBlank())
            cover = new MusicCatalogApplicationService.MediaView("", fallback, null, (Long) null);
        return new MusicCatalogApplicationService.TrackView(
                t.id(),
                t.title(),
                t.artist(),
                t.sourceId(),
                t.enabled(),
                t.sortOrder(),
                mediaView(projection(prepared.media(), t.audioAssetId())),
                cover,
                mediaView(projection(prepared.media(), t.lyricsAssetId())),
                row.version());
    }

    private static MusicCatalogApplicationService.MediaView mediaView(MediaAssetProjection a) {
        return a == null
                ? null
                : new MusicCatalogApplicationService.MediaView(
                        a.objectKey(), a.url(), a.mimeType(), a.size());
    }

    private static MediaAssetProjection projection(Map<UUID, MediaAssetProjection> media, UUID id) {
        return id == null ? null : media.get(id);
    }

    private static boolean eligible(MediaAssetProjection a, MediaKind kind) {
        return a != null && a.kind() == kind && a.status() == MediaAssetStatus.ACTIVE;
    }

    private static String coverUrl(PersistedMusicTrack track, Prepared prepared) {
        var cover = projection(prepared.media(), track.coverAssetId());
        if (eligible(cover, MediaKind.IMAGE)) return cover.url();
        if (track.externalCoverUrl() != null && !track.externalCoverUrl().isBlank())
            return track.externalCoverUrl();
        return "";
    }

    private static void addReference(
            List<MediaReference> refs,
            Map<UUID, MediaAssetProjection> media,
            PersistedMusicTrack track,
            UUID assetId,
            MediaKind kind,
            String role) {
        if (assetId == null) return;
        var asset = media.get(assetId);
        if (asset == null) throw new MusicCatalogException(UNAVAILABLE);
        refs.add(
                new MediaReference(
                        kind,
                        new MediaObjectKey(asset.objectKey()),
                        "music_track",
                        track.id(),
                        track.title() + " · " + role));
    }

    private static MusicTrackMetadata metadata(
            String title, String artist, String sourceId, boolean enabled, int sortOrder) {
        try {
            return new MusicTrackMetadata(title, artist, sourceId, enabled, sortOrder);
        } catch (IllegalArgumentException invalid) {
            throw new MusicCatalogException(INVALID_REQUEST);
        }
    }

    private static void requireVersion(Long version) {
        if (version == null) throw new MusicCatalogException(MISSING_VERSION);
        if (version < 0) throw new MusicCatalogException(INVALID_REQUEST);
    }

    private static <T> T read(Supplier<T> query) {
        try {
            return query.get();
        } catch (MusicCatalogException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new MusicCatalogException(UNAVAILABLE);
        }
    }

    private record Prepared(Map<UUID, MediaAssetProjection> media) {}
}
