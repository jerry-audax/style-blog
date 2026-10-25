package com.blogsystem.infrastructure.mybatis.mapper.music;

import com.blogsystem.domain.music.model.PersistedMusicTrack;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.Map;

@Mapper
public interface MusicTrackMetadataMapper {
    @Insert(
            """
INSERT INTO music_track
  (id, title, artist, source_id, audio_asset_id, cover_asset_id,
   lyrics_asset_id, external_cover_url, enabled, sort_order,
   version, created_at, updated_at)
VALUES
  (#{track.id}, #{track.title}, #{track.artist}, #{track.sourceId,jdbcType=VARCHAR},
   #{track.audioAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},
   #{track.coverAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},
   #{track.lyricsAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},
   #{track.externalCoverUrl,jdbcType=VARCHAR}, #{track.enabled}, #{track.sortOrder}, 0, #{now}, #{now})
ON CONFLICT (id) DO NOTHING
""")
    int insertIfAbsent(@Param("track") PersistedMusicTrack track, @Param("now") OffsetDateTime now);

    @Select(
            """
            SELECT id, title, artist, source_id, audio_asset_id, cover_asset_id,
                   lyrics_asset_id, external_cover_url, enabled, sort_order
              FROM music_track
             WHERE id = #{id}
            """)
    Map<String, Object> selectById(@Param("id") String id);
}
