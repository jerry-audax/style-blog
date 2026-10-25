package com.blogsystem.infrastructure.filesystem.music;

import com.blogsystem.infrastructure.filesystem.music.JsonPlaylistSnapshotRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;

import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.infrastructure.configuration.music.MusicProperties;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonPlaylistSnapshotRepositoryTest {
    @TempDir
    Path root;

    @Test
    void snapshotPersistsAcrossRepositoryInstancesAndCanBeCleared() throws Exception {
        var properties = new MusicProperties();
        properties.setStateDirectory(root.toString());
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var repo = new JsonPlaylistSnapshotRepository(properties, json);
        assertTrue(repo.find().isEmpty());
        var playlist = new MusicPlaylist("939817038", "博客歌单", Instant.parse("2026-10-03T00:00:00Z"), List.of());
        repo.save(playlist);
        assertEquals(playlist, new JsonPlaylistSnapshotRepository(properties, json).find().orElseThrow());
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
        repo.clear();
        assertTrue(repo.find().isEmpty());
    }
}
