package com.blogsystem.application.music.service;

import com.blogsystem.domain.music.gateway.MusicAccountGateway;
import com.blogsystem.domain.music.model.MusicAccount;
import com.blogsystem.domain.music.model.MusicException;
import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MusicApplicationService {
    private final MusicAccountGateway accounts;
    private final PlaylistSnapshotRepository snapshots;
    private final MusicPolicy policy;
    private final ReentrantLock mutations = new ReentrantLock();

    public MusicApplicationService(
            MusicAccountGateway accounts,
            PlaylistSnapshotRepository snapshots,
            MusicPolicy policy) {
        this.accounts = accounts;
        this.snapshots = snapshots;
        this.policy = policy;
    }

    public record Summary(String playlistId, String name, Instant syncedAt, int songCount) {}

    public record Status(
            boolean configured, boolean authorized, String playlistId, Summary playlist) {}

    public record PublicSong(String id, String name, String artist, String cover) {}

    public record PublicPlaylist(
            String playlistId, String name, Instant syncedAt, List<PublicSong> songs) {}

    public Status status() {
        var status = accounts.status();
        var summary =
                playlist()
                        .map(
                                p ->
                                        new Summary(
                                                p.playlistId(),
                                                p.name(),
                                                p.syncedAt(),
                                                p.songs().size()))
                        .orElse(null);
        return new Status(status.configured(), status.authorized(), policy.playlistId(), summary);
    }

    public Optional<MusicPlaylist> playlist() {
        return snapshots.find().filter(p -> policy.playlistId().equals(p.playlistId()));
    }

    public Optional<PublicPlaylist> publicPlaylist() {
        return playlist()
                .map(
                        p ->
                                new PublicPlaylist(
                                        p.playlistId(),
                                        p.name(),
                                        p.syncedAt(),
                                        p.songs().stream()
                                                .map(
                                                        s ->
                                                                new PublicSong(
                                                                        s.id(),
                                                                        s.name(),
                                                                        s.artist(),
                                                                        s.cover()))
                                                .toList()));
    }

    public MusicAccount.Authorization authorize() {
        lock();
        try {
            return accounts.authorize();
        } finally {
            mutations.unlock();
        }
    }

    public MusicPlaylist synchronize() {
        lock();
        try {
            var playlist = accounts.synchronize(policy.playlistId());
            if (!policy.playlistId().equals(playlist.playlistId()))
                throw new MusicException(MusicException.Reason.INVALID_RESPONSE);
            snapshots.save(playlist);
            return playlist;
        } finally {
            mutations.unlock();
        }
    }

    public MusicAccount.Revocation revoke() {
        lock();
        try {
            var result = accounts.revoke();
            snapshots.clear();
            return result;
        } finally {
            mutations.unlock();
        }
    }

    private void lock() {
        if (!mutations.tryLock()) throw new MusicException(MusicException.Reason.BUSY);
    }
}
