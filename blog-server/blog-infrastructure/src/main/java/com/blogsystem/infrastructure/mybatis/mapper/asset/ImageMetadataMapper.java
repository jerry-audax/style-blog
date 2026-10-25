package com.blogsystem.infrastructure.mybatis.mapper.asset;

import com.blogsystem.domain.asset.image.model.query.ImageMetadataQuery;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaAssetRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ImageMetadataMapper {
    String FILTER = """
             provider=#{q.provider} AND left(object_key,length(#{q.root.value})+1)=#{q.root.value}||'/'
             AND media_kind='IMAGE' AND status='ACTIVE'
             AND (#{q.search}='' OR strpos(lower(file_name),lower(#{q.search}))>0)
            """;

    @ResultMap("com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper.mediaAssetResult")
    @Select("SELECT " + MediaAssetMapper.COLUMNS + " FROM media_asset WHERE " + FILTER
            + " ORDER BY created_at DESC,id ASC LIMIT #{q.limit} OFFSET #{q.offset}")
    List<MediaAssetRecord> findPage(@Param("q") ImageMetadataQuery query);

    @Select("SELECT count(*) FROM media_asset WHERE " + FILTER)
    long count(@Param("q") ImageMetadataQuery query);
}
