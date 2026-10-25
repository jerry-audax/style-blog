package com.blogsystem.infrastructure.mybatis.mapper.music;

import com.blogsystem.domain.music.model.MusicTrackMetadata;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.infrastructure.mybatis.record.music.MusicTrackRecord;
import com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface MusicTrackMapper {
    String COLUMNS =
            "t.id,t.title,t.artist,t.source_id,t.audio_asset_id,t.cover_asset_id,t.lyrics_asset_id,"
                + "t.external_cover_url,t.enabled,t.sort_order,t.version,t.created_at,t.updated_at";

    @Results(id = "musicRuntimeRow")
    @ConstructorArgs({
        @Arg(column = "id", javaType = String.class),
        @Arg(column = "title", javaType = String.class),
        @Arg(column = "artist", javaType = String.class),
        @Arg(column = "source_id", javaType = String.class),
        @Arg(
                column = "audio_asset_id",
                javaType = UUID.class,
                jdbcType = JdbcType.OTHER,
                typeHandler = UuidTypeHandler.class),
        @Arg(
                column = "cover_asset_id",
                javaType = UUID.class,
                jdbcType = JdbcType.OTHER,
                typeHandler = UuidTypeHandler.class),
        @Arg(
                column = "lyrics_asset_id",
                javaType = UUID.class,
                jdbcType = JdbcType.OTHER,
                typeHandler = UuidTypeHandler.class),
        @Arg(column = "external_cover_url", javaType = String.class),
        @Arg(column = "enabled", javaType = boolean.class),
        @Arg(column = "sort_order", javaType = int.class),
        @Arg(column = "version", javaType = long.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "updated_at", javaType = OffsetDateTime.class)
    })
    @Select("SELECT " + COLUMNS + " FROM music_track t WHERE t.id=#{id} AND t.deleted_at IS NULL")
    MusicTrackRecord selectById(@Param("id") String id);

    @ResultMap("musicRuntimeRow")
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("SELECT " + COLUMNS + " FROM music_track t WHERE t.id=#{id} AND t.deleted_at IS NULL FOR UPDATE")
    MusicTrackRecord selectByIdForUpdate(@Param("id") String id);

    @ResultMap("musicRuntimeRow")
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM music_track t WHERE t.deleted_at IS NULL ORDER BY t.sort_order,t.id")
    List<MusicTrackRecord> selectAdminList();

    @ResultMap("musicRuntimeRow")
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM music_track t JOIN media_asset a ON a.id=t.audio_asset_id WHERE"
                    + " t.deleted_at IS NULL AND t.enabled=true AND a.status='ACTIVE' AND"
                    + " a.media_kind='AUDIO' ORDER BY t.sort_order,t.id LIMIT #{limit}")
    List<MusicTrackRecord> selectPublicCandidates(@Param("limit") int limit);

    @Insert(
            """
INSERT INTO music_track(id,title,artist,source_id,audio_asset_id,cover_asset_id,
    lyrics_asset_id,external_cover_url,enabled,sort_order,version,created_at,updated_at)
VALUES(#{track.id},#{track.title},#{track.artist},#{track.sourceId,jdbcType=VARCHAR},
    NULL,NULL,NULL,NULL,#{track.enabled},#{track.sortOrder},0,#{now},#{now})
""")
    int insert(@Param("track") PersistedMusicTrack track, @Param("now") OffsetDateTime now);

    @Update(
            """
            UPDATE music_track SET title=#{metadata.title},artist=#{metadata.artist},
                source_id=#{metadata.sourceId,jdbcType=VARCHAR},enabled=#{metadata.enabled},
                sort_order=#{metadata.sortOrder},version=version+1,updated_at=#{now}
            WHERE id=#{id} AND version=#{expectedVersion} AND version<9223372036854775807 AND deleted_at IS NULL
            """)
    int updateMetadata(
            @Param("id") String id,
            @Param("metadata") MusicTrackMetadata metadata,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") OffsetDateTime now);

    @Update(
            """
UPDATE music_track SET audio_asset_id=#{newAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},version=version+1,updated_at=#{now}
WHERE id=#{id} AND version=#{expectedVersion} AND version<9223372036854775807 AND deleted_at IS NULL
    AND audio_asset_id IS NOT DISTINCT FROM #{expectedOldAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}
    AND EXISTS (SELECT 1 FROM media_asset a WHERE a.id=#{newAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER} AND a.status='ACTIVE' AND a.media_kind='AUDIO')
""")
    int bindAudio(
            @Param("id") String id,
            @Param("newAssetId") UUID newAssetId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedOldAssetId") UUID expectedOldAssetId,
            @Param("now") OffsetDateTime now);

    @Update(
            """
UPDATE music_track SET cover_asset_id=#{newAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},version=version+1,updated_at=#{now}
WHERE id=#{id} AND version=#{expectedVersion} AND version<9223372036854775807 AND deleted_at IS NULL
    AND cover_asset_id IS NOT DISTINCT FROM #{expectedOldAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}
    AND EXISTS (SELECT 1 FROM media_asset a WHERE a.id=#{newAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER} AND a.status='ACTIVE' AND a.media_kind='IMAGE')
""")
    int bindCover(
            @Param("id") String id,
            @Param("newAssetId") UUID newAssetId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedOldAssetId") UUID expectedOldAssetId,
            @Param("now") OffsetDateTime now);

    @Update(
            """
UPDATE music_track SET lyrics_asset_id=#{newAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},version=version+1,updated_at=#{now}
WHERE id=#{id} AND version=#{expectedVersion} AND version<9223372036854775807 AND deleted_at IS NULL
    AND lyrics_asset_id IS NOT DISTINCT FROM #{expectedOldAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}
    AND EXISTS (SELECT 1 FROM media_asset a WHERE a.id=#{newAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER} AND a.status='ACTIVE' AND a.media_kind='LYRICS')
""")
    int bindLyrics(
            @Param("id") String id,
            @Param("newAssetId") UUID newAssetId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedOldAssetId") UUID expectedOldAssetId,
            @Param("now") OffsetDateTime now);

    @Update("""
        UPDATE music_track SET deleted_at=#{now},updated_at=#{now},version=version+1
        WHERE id=#{id} AND deleted_at IS NULL AND version=#{expectedVersion}
          AND version<9223372036854775807 AND updated_at<=#{now}
        """)
    int softDelete(@Param("id") String id,@Param("expectedVersion") long expectedVersion,@Param("now") OffsetDateTime now);

    @Update("""
        <script>
        UPDATE music_track SET
        <choose>
          <when test="kind == 'AUDIO'">audio_asset_id=NULL,</when>
          <when test="kind == 'COVER'">cover_asset_id=NULL,external_cover_url=NULL,</when>
          <otherwise>lyrics_asset_id=NULL,</otherwise>
        </choose>
        updated_at=#{now},version=version+1
        WHERE id=#{id} AND deleted_at IS NULL AND version=#{expectedVersion}
          AND version &lt; 9223372036854775807 AND updated_at &lt;= #{now}
          AND
        <choose>
          <when test="kind == 'AUDIO'">audio_asset_id</when>
          <when test="kind == 'COVER'">cover_asset_id</when>
          <otherwise>lyrics_asset_id</otherwise>
        </choose>
        IS NOT DISTINCT FROM #{expectedAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}
        </script>
        """)
    int unbindMedia(@Param("id") String id,@Param("kind") String kind,@Param("expectedAssetId") UUID expectedAssetId,
            @Param("expectedVersion") long expectedVersion,@Param("now") OffsetDateTime now);
}
