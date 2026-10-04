package com.blogsystem.content.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.content.infrastructure.persistence.ArticleTag;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArticleTagMapper extends BaseMapper<ArticleTag> {
}
