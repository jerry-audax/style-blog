package com.blogsystem.infrastructure.filesystem.music;

import com.blogsystem.domain.music.model.MusicException;
import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;
import com.blogsystem.infrastructure.configuration.music.MusicProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Local metadata snapshot only; image/file module and /uploads are not restored.
 */
@Repository
public class JsonPlaylistSnapshotRepository implements PlaylistSnapshotRepository {
    private final Path file;
    private final ObjectMapper json;

    public JsonPlaylistSnapshotRepository(MusicProperties properties, ObjectMapper json) {
        this.file = properties.state().resolve("public-playlist.json");
        this.json = json;
    }

    @Override
    public synchronized Optional<MusicPlaylist> find() {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(json.readValue(file.toFile(), MusicPlaylist.class));
        } catch (IOException | IllegalArgumentException error) {
            throw new MusicException(MusicException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public synchronized void save(MusicPlaylist playlist) {
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "playlist-", ".tmp");
            json.writeValue(temp.toFile(), playlist);
            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new MusicException(MusicException.Reason.UNAVAILABLE);
        } finally {
            if (temp != null)
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
        }
    }

    @Override
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException error) {
            throw new MusicException(MusicException.Reason.UNAVAILABLE);
        }
    }
}
