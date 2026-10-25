package com.blogsystem.infrastructure.mybatis.mapper.asset;

import com.blogsystem.infrastructure.mybatis.record.asset.MediaDeletionAssetRecord;
import com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Mapper
public interface MediaDeletionAssetMapper {
    String COLUMNS =
            MediaAssetMapper.COLUMNS
                    + ",deletion_scope,exclusive_environment,ownership_confirmed_by,ownership_confirmed_at,ownership_note";
    String UUID_PARAM =
            "#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler}";

    @Results(
            id = "deletionAsset",
            value = @Result(column = "id", property = "id", typeHandler = UuidTypeHandler.class))
    @Select("SELECT " + COLUMNS + " FROM media_asset WHERE id=" + UUID_PARAM)
    MediaDeletionAssetRecord selectById(@Param("id") UUID id);

    @ResultMap("deletionAsset")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            "<script>SELECT "
                    + COLUMNS
                    + " FROM media_asset WHERE id IN <foreach collection='ids' item='id' open='('"
                    + " separator=',' close=')'>"
                    + UUID_PARAM
                    + "</foreach> ORDER BY id FOR UPDATE</script>")
    List<MediaDeletionAssetRecord> lockByIds(@Param("ids") Set<UUID> ids);

    @ResultMap("deletionAsset")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            affectData = true,
            value =
                    """
UPDATE media_asset SET deletion_scope=#{row.deletionScope},exclusive_environment=#{row.exclusiveEnvironment},
  ownership_confirmed_by=#{row.ownershipConfirmedBy},ownership_confirmed_at=#{row.ownershipConfirmedAt},
  ownership_note=#{row.ownershipNote},updated_at=#{row.updatedAt},version=version+1
WHERE id=#{row.id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler}
  AND status='ACTIVE' AND version=#{expectedVersion} AND updated_at <= #{row.updatedAt}
RETURNING
"""
                            + COLUMNS)
    MediaDeletionAssetRecord confirmOwnership(
            @Param("row") MediaDeletionAssetRecord row,
            @Param("expectedVersion") long expectedVersion);
}
