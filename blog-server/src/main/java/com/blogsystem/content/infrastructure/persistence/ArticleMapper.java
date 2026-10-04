package com.blogsystem.content.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.content.infrastructure.persistence.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
    @Select("SELECT * FROM article WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Article selectActiveForUpdate(@Param("id") Long id);
}
