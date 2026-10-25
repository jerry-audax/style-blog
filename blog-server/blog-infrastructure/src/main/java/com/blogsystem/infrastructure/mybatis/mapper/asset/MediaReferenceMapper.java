package com.blogsystem.infrastructure.mybatis.mapper.asset;

import com.blogsystem.infrastructure.mybatis.record.asset.MediaArticleReferenceRecord;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaOwnerAvatarReferenceRecord;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaReferenceBindingRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** Deliberately not public-list queries: disabled/draft rows still protect their references. */
@Mapper
public interface MediaReferenceMapper {
    String ASSET = "#{assetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler}";

    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false, timeout = 10)
    @Select("SELECT id AS business_id,NULL::text AS resource_url,'AUDIO' AS slot FROM music_track WHERE deleted_at IS NULL AND audio_asset_id=" + ASSET
            + " UNION ALL SELECT id,NULL::text,'COVER' FROM music_track WHERE deleted_at IS NULL AND cover_asset_id=" + ASSET
            + " UNION ALL SELECT id,NULL::text,'LYRICS' FROM music_track WHERE deleted_at IS NULL AND lyrics_asset_id=" + ASSET)
    List<MediaReferenceBindingRecord> selectMusicBindings(@Param("assetId") UUID assetId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false, timeout = 10)
    @Select("SELECT id AS business_id,NULL::text AS resource_url,'VIDEO' AS slot FROM surprise_video WHERE deleted_at IS NULL AND video_asset_id=" + ASSET)
    List<MediaReferenceBindingRecord> selectVideoBindings(@Param("assetId") UUID assetId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false, timeout = 10)
    @Select("SELECT id AS business_id,external_cover_url AS resource_url,'COVER' AS slot FROM music_track WHERE deleted_at IS NULL AND external_cover_url IS NOT NULL")
    List<MediaReferenceBindingRecord> selectMusicExternalCovers();

    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false, timeout = 10)
    @Select("SELECT id,cover_url,content_md,content_html FROM article WHERE deleted=0 ORDER BY id")
    List<MediaArticleReferenceRecord> selectArticleResources();

    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false, timeout = 10)
    @Select("SELECT id,avatar FROM sys_user WHERE deleted=0 ORDER BY id")
    List<MediaOwnerAvatarReferenceRecord> selectOwnerAvatars();
}
