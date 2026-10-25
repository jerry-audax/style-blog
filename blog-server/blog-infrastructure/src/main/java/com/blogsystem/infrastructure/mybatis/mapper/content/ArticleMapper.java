package com.blogsystem.infrastructure.mybatis.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ArticleMapper extends BaseMapper<ArticleRecord> {
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM article WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    ArticleRecord selectActiveForUpdate(@Param("id") Long id);
}
