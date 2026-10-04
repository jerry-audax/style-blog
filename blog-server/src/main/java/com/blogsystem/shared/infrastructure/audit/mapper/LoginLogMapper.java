package com.blogsystem.shared.infrastructure.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.shared.infrastructure.audit.entity.LoginLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {
}
