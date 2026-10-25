package com.blogsystem.infrastructure.mybatis.mapper.interaction;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.infrastructure.mybatis.record.interaction.CommentRecord;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommentMapper extends BaseMapper<CommentRecord> {}
