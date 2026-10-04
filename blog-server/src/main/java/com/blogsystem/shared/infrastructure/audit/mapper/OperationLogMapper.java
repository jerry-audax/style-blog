package com.blogsystem.shared.infrastructure.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.shared.infrastructure.audit.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
