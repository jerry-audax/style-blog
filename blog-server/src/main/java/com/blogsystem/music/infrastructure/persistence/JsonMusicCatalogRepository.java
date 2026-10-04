package com.blogsystem.music.infrastructure.persistence;

import com.blogsystem.music.domain.model.MusicException;
import com.blogsystem.music.domain.model.MusicTrack;
import com.blogsystem.music.domain.repository.MusicCatalogRepository;
import com.blogsystem.music.infrastructure.configuration.MusicProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Optional;

@Repository
public class JsonMusicCatalogRepository implements MusicCatalogRepository {
    private final Path file;
    private final ObjectMapper json;

    public JsonMusicCatalogRepository(MusicProperties properties, ObjectMapper json) {
        this.file = properties.state().resolve("music-catalog.json").normalize();
        this.json = json;
    }

    @Override
    public synchronized Optional<List<MusicTrack>> find() {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            List<MusicTrack> value = json.readValue(file.toFile(), new TypeReference<>() {});
            if (value == null || value.size() > 2000) throw new IllegalArgumentException();
            return Optional.of(List.copyOf(value));
        } catch (IOException | IllegalArgumentException error) {
            throw new MusicException(MusicException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public synchronized void save(List<MusicTrack> tracks) {
        if (tracks == null || tracks.size() > 2000) throw new IllegalArgumentException("音乐目录过大");
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "music-catalog-", ".tmp");
            json.writeValue(temp.toFile(), List.copyOf(tracks));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new MusicException(MusicException.Reason.UNAVAILABLE);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }
}
