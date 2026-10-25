package com.blogsystem.infrastructure.adapter.repository.music;

import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackMetadataRepository;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMetadataMapper;

import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.blogsystem.domain.music.model.PersistedMusicTrack;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MybatisMusicTrackMetadataRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-10-13T01:02:03.123456Z");

    @Test
    void reportsWhetherTheImportActuallyInsertedARow() {
        MusicTrackMetadataMapper mapper = mock(MusicTrackMetadataMapper.class);
        MybatisMusicTrackMetadataRepository repository = new MybatisMusicTrackMetadataRepository(mapper);
        PersistedMusicTrack track = new PersistedMusicTrack("existing-01", "Song", "", null,
                null, null, null, null, true, 0);
        when(mapper.insertIfAbsent(eq(track), any(OffsetDateTime.class))).thenReturn(1, 0);

        assertTrue(repository.insertIfAbsent(track, NOW));
        assertFalse(repository.insertIfAbsent(track, NOW));
        verify(mapper, times(2)).insertIfAbsent(track, NOW.atOffset(ZoneOffset.UTC));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void restoresUuidObjectsAndStringsAtTheInfrastructureBoundary() {
        MusicTrackMetadataMapper mapper = mock(MusicTrackMetadataMapper.class);
        Map<String, Object> row = new HashMap<>();
        row.put("id", "existing-01");
        row.put("title", "Existing editor title");
        row.put("artist", "Artist");
        row.put("source_id", null);
        row.put("audio_asset_id", UUID.fromString("b6304881-436a-45fe-b29f-7c4704512260"));
        row.put("cover_asset_id", "45648b6c-50f1-4963-8b3a-801b2750f1c5");
        row.put("lyrics_asset_id", null);
        row.put("external_cover_url", "https://example.test/cover.jpg");
        row.put("enabled", false);
        row.put("sort_order", 12);
        when(mapper.selectById("existing-01")).thenReturn(row);

        PersistedMusicTrack loaded = new MybatisMusicTrackMetadataRepository(mapper)
                .findById("existing-01").orElseThrow();

        assertEquals(new PersistedMusicTrack("existing-01", "Existing editor title", "Artist", null,
                UUID.fromString("b6304881-436a-45fe-b29f-7c4704512260"),
                UUID.fromString("45648b6c-50f1-4963-8b3a-801b2750f1c5"), null,
                "https://example.test/cover.jpg", false, 12), loaded);
    }

    @Test
    void returnsEmptyWhenTheCatalogueIdentityDoesNotExist() {
        MusicTrackMetadataMapper mapper = mock(MusicTrackMetadataMapper.class);
        when(mapper.selectById("missing")).thenReturn(null);

        assertTrue(new MybatisMusicTrackMetadataRepository(mapper).findById("missing").isEmpty());
    }
}
