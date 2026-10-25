package com.blogsystem.infrastructure.mybatis.mapper.surprise;

import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.infrastructure.mybatis.record.surprise.SurpriseVideoMetadataRecord;
import com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Mapper
public interface SurpriseVideoMetadataMapper {
    @Insert(
            """
INSERT INTO surprise_video
  (id, title, video_asset_id, enabled, sort_order, version, created_at, updated_at)
VALUES
  (#{video.id}, #{video.title},
   #{video.videoAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},
   #{video.enabled}, #{video.sortOrder}, 0, #{now}, #{now})
ON CONFLICT (id) DO NOTHING
""")
    int insertIfAbsent(
            @Param("video") PersistedSurpriseVideo video, @Param("now") OffsetDateTime now);

    @ConstructorArgs({
        @Arg(column = "id", javaType = String.class),
        @Arg(column = "title", javaType = String.class),
        @Arg(
                column = "video_asset_id",
                javaType = UUID.class,
                jdbcType = JdbcType.OTHER,
                typeHandler = UuidTypeHandler.class),
        @Arg(column = "enabled", javaType = boolean.class),
        @Arg(column = "sort_order", javaType = int.class)
    })
    @Select(
            """
            SELECT id, title, video_asset_id, enabled, sort_order
              FROM surprise_video
             WHERE id = #{id}
            """)
    SurpriseVideoMetadataRecord selectById(@Param("id") String id);
}
