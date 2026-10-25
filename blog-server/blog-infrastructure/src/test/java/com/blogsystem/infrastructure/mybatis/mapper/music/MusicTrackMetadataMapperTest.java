package com.blogsystem.infrastructure.mybatis.mapper.music;

import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMetadataMapper;

import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicTrackMetadataMapperTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-13T01:02:03.123456Z");

    @Test
    void emitsAnInsertThatKeepsConflictingRowsAndBindsImportedMetadata() throws Exception {
        UUID audio = UUID.fromString("b6304881-436a-45fe-b29f-7c4704512260");
        String title = "Song'); DELETE FROM music_track; --";
        PersistedMusicTrack track = new PersistedMusicTrack("existing-01", title, "", null,
                audio, null, null, null, true, 7);
        Map<String, Object> parameters = Map.of("track", track, "now", NOW);
        MappedStatement mapped = statement("insertIfAbsent");
        BoundSql bound = mapped.getBoundSql(parameters);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.startsWith("INSERT INTO music_track"));
        assertTrue(sql.endsWith("ON CONFLICT (id) DO NOTHING"));
        assertFalse(sql.contains(title));
        assertFalse(sql.contains("DO UPDATE"));
        assertTrue(sql.contains("version, created_at, updated_at"));
        assertTrue(sql.contains("?, ?, 0, ?, ?"));

        PreparedStatement prepared = mock(PreparedStatement.class);
        new DefaultParameterHandler(mapped, parameters, bound).setParameters(prepared);

        verify(prepared).setString(1, "existing-01");
        verify(prepared).setString(2, title);
        verify(prepared).setString(3, "");
        verify(prepared).setNull(4, Types.VARCHAR);
        verify(prepared).setObject(5, audio, Types.OTHER);
        verify(prepared).setNull(6, Types.OTHER);
        verify(prepared).setNull(7, Types.OTHER);
        verify(prepared).setNull(8, Types.VARCHAR);
        verify(prepared).setBoolean(9, true);
        verify(prepared).setInt(10, 7);
        verify(prepared).setObject(11, NOW);
        verify(prepared).setObject(12, NOW);
    }

    @Test
    void bindsLookupIdentityInsteadOfInterpolatingIt() throws Exception {
        String id = "existing' OR '1'='1";
        Map<String, Object> parameters = Map.of("id", id);
        MappedStatement mapped = statement("selectById");
        BoundSql bound = mapped.getBoundSql(parameters);

        assertTrue(bound.getSql().replaceAll("\\s+", " ").trim().endsWith("WHERE id = ?"));
        assertFalse(bound.getSql().contains(id));
        PreparedStatement prepared = mock(PreparedStatement.class);
        new DefaultParameterHandler(mapped, parameters, bound).setParameters(prepared);
        verify(prepared).setString(1, id);
    }

    private static MappedStatement statement(String method) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(MusicTrackMetadataMapper.class);
        return configuration.getMappedStatement(MusicTrackMetadataMapper.class.getName() + "." + method);
    }
}
