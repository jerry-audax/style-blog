package com.blogsystem.infrastructure.mybatis.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleTagRecord;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArticleTagMapper extends BaseMapper<ArticleTagRecord> {}
