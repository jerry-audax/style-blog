package com.blogsystem.music.infrastructure.persistence;

import com.blogsystem.music.domain.model.*;
import com.blogsystem.music.domain.repository.PlaylistSnapshotRepository;
import com.blogsystem.music.infrastructure.configuration.MusicProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.nio.file.*;
import java.util.Optional;
import java.io.IOException;

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
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new MusicException(MusicException.Reason.UNAVAILABLE);
        } finally {
            if (temp != null) try {
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
