package com.blogsystem.content.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.content.infrastructure.persistence.ArticleLike;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArticleLikeMapper extends BaseMapper<ArticleLike> {
}
