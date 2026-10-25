package com.blogsystem.app.it.music.infrastructure.cli;

import com.blogsystem.infrastructure.gateway.netease.OfficialCliMusicRepository;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

import com.blogsystem.application.music.service.MusicApplicationService;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.infrastructure.configuration.music.MusicProperties;
import com.blogsystem.infrastructure.filesystem.music.JsonPlaylistSnapshotRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit opt-in: reads the real owner's selected playlist and saves its public metadata only.
 */
class MusicSmokeTest {
    @Test
    void realOfficialCliAndSnapshotSync() {
        assumeTrue("true".equals(System.getProperty("music.smoke")));
        var properties = new MusicProperties();
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var accounts = new OfficialCliMusicRepository(properties, json);
        var snapshots = new JsonPlaylistSnapshotRepository(properties, json);
        var service = new MusicApplicationService(accounts, snapshots, new MusicPolicy(properties.getPlaylistId()));
        assertTrue(accounts.status().authorized());
        var result = service.synchronize();
        assertEquals(properties.getPlaylistId(), result.playlistId());
        assertFalse(result.songs().isEmpty());
        assertEquals(result, snapshots.find().orElseThrow());
        System.out.println("Official CLI smoke: selected playlist synced; songs=" + result.songs().size());
    }
}
