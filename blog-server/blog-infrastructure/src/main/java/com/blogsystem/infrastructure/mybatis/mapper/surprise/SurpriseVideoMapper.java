package com.blogsystem.infrastructure.mybatis.mapper.surprise;

import com.blogsystem.domain.surprise.model.SurpriseVideoMetadata;
import com.blogsystem.infrastructure.mybatis.record.surprise.SurpriseVideoRecord;
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
public interface SurpriseVideoMapper {
    String COLUMNS =
            "t.id,t.title,t.video_asset_id,t.enabled,t.sort_order,t.version,t.created_at,t.updated_at";

    @Results(id = "surpriseRuntimeRow")
    @ConstructorArgs({
        @Arg(column = "id", javaType = String.class),
        @Arg(column = "title", javaType = String.class),
        @Arg(
                column = "video_asset_id",
                javaType = UUID.class,
                jdbcType = JdbcType.OTHER,
                typeHandler = UuidTypeHandler.class),
        @Arg(column = "enabled", javaType = boolean.class),
        @Arg(column = "sort_order", javaType = int.class),
        @Arg(column = "version", javaType = long.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "updated_at", javaType = OffsetDateTime.class)
    })
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM surprise_video t WHERE t.id=#{id} AND t.deleted_at IS NULL")
    SurpriseVideoRecord selectById(@Param("id") String id);

    @ResultMap("surpriseRuntimeRow")
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("SELECT " + COLUMNS + " FROM surprise_video t WHERE t.id=#{id} AND t.deleted_at IS NULL FOR UPDATE")
    SurpriseVideoRecord selectByIdForUpdate(@Param("id") String id);

    @ResultMap("surpriseRuntimeRow")
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM surprise_video t WHERE t.deleted_at IS NULL ORDER BY"
                    + " t.sort_order,t.id")
    List<SurpriseVideoRecord> selectAdminList();

    @ResultMap("surpriseRuntimeRow")
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM surprise_video t JOIN media_asset a ON a.id=t.video_asset_id WHERE"
                    + " t.deleted_at IS NULL AND t.enabled=true AND a.status='ACTIVE' AND"
                    + " a.media_kind='VIDEO' ORDER BY t.sort_order,t.id")
    List<SurpriseVideoRecord> selectPublicCandidates();

    @Update(
            """
            UPDATE surprise_video SET title=#{metadata.title},enabled=#{metadata.enabled},
                sort_order=#{metadata.sortOrder},version=version+1,updated_at=#{now}
            WHERE id=#{id} AND version=#{expectedVersion} AND version<9223372036854775807 AND deleted_at IS NULL
            """)
    int updateMetadata(
            @Param("id") String id,
            @Param("metadata") SurpriseVideoMetadata metadata,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") OffsetDateTime now);

    @Insert(
            """
INSERT INTO surprise_video(id,title,video_asset_id,enabled,sort_order,version,created_at,updated_at)
SELECT #{video.id},#{video.title},#{video.videoAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},#{video.enabled},#{video.sortOrder},0,#{now},#{now}
WHERE EXISTS (SELECT 1 FROM media_asset a WHERE a.id=#{video.videoAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER} AND a.status='ACTIVE' AND a.media_kind='VIDEO')
""")
    int insertVideo(
            @Param("video") com.blogsystem.domain.surprise.model.PersistedSurpriseVideo video,
            @Param("now") OffsetDateTime now);

    @Update("""
        UPDATE surprise_video SET deleted_at=#{now},updated_at=#{now},version=version+1
        WHERE id=#{id} AND deleted_at IS NULL AND version=#{expectedVersion}
          AND version<9223372036854775807 AND updated_at<=#{now}
        """)
    int softDelete(@Param("id") String id,@Param("expectedVersion") long expectedVersion,@Param("now") OffsetDateTime now);
}
