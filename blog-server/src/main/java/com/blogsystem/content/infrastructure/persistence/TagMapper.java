package com.blogsystem.content.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.content.infrastructure.persistence.Tag;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {
}
