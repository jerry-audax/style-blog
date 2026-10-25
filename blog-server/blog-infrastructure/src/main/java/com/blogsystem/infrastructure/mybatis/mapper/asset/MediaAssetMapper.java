package com.blogsystem.infrastructure.mybatis.mapper.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaAssetRecord;
import com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.OffsetDateTimeTypeHandler;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Mapper
public interface MediaAssetMapper {
    String COLUMNS =
            """
            id, provider, object_key, media_kind, file_name, mime_type,
            file_size, sha256, width, height, duration_ms, status, version,
            created_at, updated_at
            """;

    @Results(
            id = "mediaAssetResult",
            value = {
                @Result(
                        column = "id",
                        property = "id",
                        jdbcType = JdbcType.OTHER,
                        typeHandler = UuidTypeHandler.class),
                @Result(column = "provider", property = "provider"),
                @Result(column = "object_key", property = "objectKey"),
                @Result(column = "media_kind", property = "mediaKind"),
                @Result(column = "file_name", property = "fileName"),
                @Result(column = "mime_type", property = "mimeType"),
                @Result(column = "file_size", property = "fileSize"),
                @Result(column = "sha256", property = "sha256"),
                @Result(column = "width", property = "width"),
                @Result(column = "height", property = "height"),
                @Result(column = "duration_ms", property = "durationMs"),
                @Result(column = "status", property = "status"),
                @Result(column = "version", property = "version"),
                @Result(
                        column = "created_at",
                        property = "createdAt",
                        typeHandler = OffsetDateTimeTypeHandler.class),
                @Result(
                        column = "updated_at",
                        property = "updatedAt",
                        typeHandler = OffsetDateTimeTypeHandler.class)
            })
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM media_asset WHERE id ="
                    + " #{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}")
    MediaAssetRecord selectById(@Param("id") UUID id);

    @ResultMap("mediaAssetResult")
    @Select(
            """
<script>
SELECT id, provider, object_key, media_kind, file_name, mime_type,
       file_size, sha256, width, height, duration_ms, status, version, created_at, updated_at
  FROM media_asset
 WHERE id IN
 <foreach collection="ids" item="id" open="(" separator="," close=")">
   #{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}
 </foreach>
 ORDER BY id
</script>
""")
    List<MediaAssetRecord> selectByIds(@Param("ids") Set<UUID> ids);

    @ResultMap("mediaAssetResult")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT " + COLUMNS + " FROM media_asset WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>"
            + "#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}"
            + "</foreach> ORDER BY id FOR UPDATE</script>")
    List<MediaAssetRecord> lockByIds(@Param("ids") Set<UUID> ids);

    @ResultMap("mediaAssetResult")
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM media_asset WHERE provider = #{provider} AND object_key ="
                    + " #{objectKey}")
    MediaAssetRecord selectByLocation(
            @Param("provider") String provider, @Param("objectKey") String objectKey);

    @ResultMap("mediaAssetResult")
    @Select("<script>SELECT " + COLUMNS + " FROM media_asset WHERE provider=#{provider} AND object_key IN "
            + "<foreach collection='keys' item='key' open='(' separator=',' close=')'>#{key.value}</foreach>"
            + " ORDER BY id</script>")
    List<MediaAssetRecord> selectByLocations(@Param("provider") String provider,
            @Param("keys") Set<MediaObjectKey> keys);

    @Insert(
            """
INSERT INTO media_asset
  (id, provider, object_key, media_kind, file_name, mime_type,
   file_size, sha256, width, height, duration_ms, status, version,
   created_at, updated_at)
VALUES
  (#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}, #{provider}, #{objectKey}, #{mediaKind}, #{fileName}, #{mimeType},
   #{fileSize}, #{sha256}, #{width}, #{height}, #{durationMs}, #{status}, #{version},
   #{createdAt}, #{updatedAt})
""")
    int insert(MediaAssetRecord record);

    /**
     * PostgreSQL's RETURNING keeps the update and the returned version atomic.
     * The identity predicates prevent a stale caller from changing a different
     * incarnation of the same UUID.
     */
    @ResultMap("mediaAssetResult")
    @Select(
            """
UPDATE media_asset
   SET file_name = #{fileName},
       mime_type = #{mimeType},
       file_size = #{fileSize},
       sha256 = #{sha256},
       width = #{width},
       height = #{height},
       duration_ms = #{durationMs},
       updated_at = #{updatedAt},
       version = version + 1
 WHERE id = #{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}
   AND provider = #{provider}
   AND object_key = #{objectKey}
   AND media_kind = #{mediaKind}
   AND status = #{status}
   AND version = #{expectedVersion}
RETURNING id, provider, object_key, media_kind, file_name, mime_type,
          file_size, sha256, width, height, duration_ms, status, version,
          created_at, updated_at
""")
    MediaAssetRecord updateMetadata(
            @Param("id") UUID id,
            @Param("provider") String provider,
            @Param("objectKey") String objectKey,
            @Param("mediaKind") String mediaKind,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("fileName") String fileName,
            @Param("mimeType") String mimeType,
            @Param("fileSize") Long fileSize,
            @Param("sha256") String sha256,
            @Param("width") Integer width,
            @Param("height") Integer height,
            @Param("durationMs") Long durationMs,
            @Param("updatedAt") java.time.OffsetDateTime updatedAt);

    @ResultMap("mediaAssetResult")
    @Select(
            """
            <script>
            SELECT id, provider, object_key, media_kind, file_name, mime_type,
                   file_size, sha256, width, height, duration_ms, status, version,
                   created_at, updated_at
              FROM media_asset
             WHERE 1 = 1
               <if test="kind != null">AND media_kind = #{kind}</if>
               <if test="status != null">AND status = #{status}</if>
             ORDER BY created_at DESC, id ASC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<MediaAssetRecord> selectPage(
            @Param("kind") String kind,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select(
            """
            <script>
            SELECT COUNT(*)
              FROM media_asset
             WHERE 1 = 1
               <if test="kind != null">AND media_kind = #{kind}</if>
               <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long count(@Param("kind") String kind, @Param("status") String status);
}
