package com.blogsystem.infrastructure.mybatis.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.infrastructure.mybatis.record.content.CategoryRecord;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CategoryMapper extends BaseMapper<CategoryRecord> {}
