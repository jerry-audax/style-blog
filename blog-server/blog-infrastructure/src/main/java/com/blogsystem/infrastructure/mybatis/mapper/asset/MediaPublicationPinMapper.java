package com.blogsystem.infrastructure.mybatis.mapper.asset;

import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationPinKey;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaPublicationPinRecord;
import com.blogsystem.infrastructure.mybatis.typehandler.UuidArrayTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface MediaPublicationPinMapper {
    String COLUMNS = "environment_id,publisher_id,release_id,manifest_digest,asset_ids,status,version,created_at,updated_at,released_at";
    String KEY = "environment_id=#{key.environmentId} AND publisher_id=#{key.publisherId} AND release_id=#{key.releaseId}";
    String ARRAY_HANDLER = "typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidArrayTypeHandler";

    @Results(id = "publicationPin", value = @Result(column = "asset_ids", property = "assetIds", typeHandler = UuidArrayTypeHandler.class))
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT " + COLUMNS + " FROM media_publication_pin WHERE " + KEY)
    MediaPublicationPinRecord selectByKey(@Param("key") MediaPublicationPinKey key);

    @ResultMap("publicationPin")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT " + COLUMNS + " FROM media_publication_pin WHERE status='HELD' AND asset_ids @> #{ids," + ARRAY_HANDLER
            + "} ORDER BY environment_id COLLATE \"C\",publisher_id COLLATE \"C\",release_id COLLATE \"C\"")
    List<MediaPublicationPinRecord> selectHolding(@Param("ids") UUID[] ids);

    @ResultMap("publicationPin")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(affectData = true, value = "INSERT INTO media_publication_pin (" + COLUMNS + ") VALUES "
            + "(#{row.environmentId},#{row.publisherId},#{row.releaseId},#{row.manifestDigest},#{row.assetIds," + ARRAY_HANDLER
            + "},#{row.status},#{row.version},#{row.createdAt},#{row.updatedAt},#{row.releasedAt}) RETURNING " + COLUMNS)
    MediaPublicationPinRecord insert(@Param("row") MediaPublicationPinRecord row);

    @ResultMap("publicationPin")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(affectData = true, value = "UPDATE media_publication_pin SET status='RELEASED',version=version+1,updated_at=#{now},released_at=#{now} "
            + "WHERE " + KEY + " AND status='HELD' AND version=#{version} AND version < 9223372036854775807 AND updated_at <= #{now} RETURNING " + COLUMNS)
    MediaPublicationPinRecord release(@Param("key") MediaPublicationPinKey key, @Param("version") long version, @Param("now") OffsetDateTime now);
}
