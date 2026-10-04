package com.blogsystem.music.application;

import com.blogsystem.music.domain.repository.*;
import com.blogsystem.music.domain.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class HostedMusicApplicationService {
    private final HostedAudioRepository audio;
    private final PlaylistSnapshotRepository snapshots;
    private final SongArtworkRepository artwork;
    private final MusicPolicy policy;
    private final MusicCatalogApplicationService ownedCatalog;

    public HostedMusicApplicationService(HostedAudioRepository audio, PlaylistSnapshotRepository snapshots,
                                         SongArtworkRepository artwork, MusicPolicy policy) {
        this(audio, snapshots, artwork, policy, null);
    }

    @Autowired
    public HostedMusicApplicationService(HostedAudioRepository audio, PlaylistSnapshotRepository snapshots,
                                         SongArtworkRepository artwork, MusicPolicy policy,
                                         MusicCatalogApplicationService ownedCatalog) {
        this.audio = audio;
        this.snapshots = snapshots;
        this.artwork = artwork;
        this.policy = policy;
        this.ownedCatalog = ownedCatalog;
    }

    public record Song(String id, String name, String artist, String cover, String url, String mediaType, long size, String lyrics) {
        public Song(String id, String name, String artist, String cover, String url, String mediaType, long size) {
            this(id, name, artist, cover, url, mediaType, size, "");
        }
    }

    public record Playlist(String source, String name, List<Song> songs) {
    }

    public Playlist playlist() {
        if (ownedCatalog != null) {
            var result = ownedCatalog.publicPlaylist();
            return new Playlist(result.source(), result.name(), result.songs().stream()
                    .map(song -> new Song(song.id(), song.name(), song.artist(), song.cover(), song.url(), "", 0, song.lyrics()))
                    .toList());
        }
        var published = audio.findPublished();
        Map<String, MusicPlaylist.Song> titles = Map.of();
        try {
            titles = snapshots.find().filter(p -> p.playlistId().equals(policy.playlistId()))
                    .map(p -> PlaylistSongMatcher.uniqueTitles(p.songs())).orElse(Map.of());
        } catch (MusicException ignored) {
            // Missing/corrupt optional metadata never hides publicly hosted audio.
        }
        final var matches = titles;
        var ids = published.stream().map(a -> matches.get(PlaylistSongMatcher.title(a.name())))
                .filter(Objects::nonNull).filter(s -> s.cover() == null || s.cover().isBlank())
                .map(MusicPlaylist.Song::id).collect(Collectors.toSet());
        var covers = ids.isEmpty() ? Map.<String, String>of() : artwork.findCovers(ids);
        return new Playlist("imgbed", "博客音乐", published.stream().map(a -> {
            var song = matches.get(PlaylistSongMatcher.title(a.name()));
            String artist = song == null || song.artist() == null ? "" : song.artist();
            String cover = song == null ? "" : song.cover() != null && !song.cover().isBlank()
                    ? song.cover() : covers.getOrDefault(song.id(), "");
            return new Song(a.id(), a.name(), artist, cover, a.url(), a.mediaType(), a.size(), "");
        }).toList());
    }
}
