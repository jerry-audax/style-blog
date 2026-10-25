package com.blogsystem.infrastructure.mybatis.mapper.administration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.infrastructure.mybatis.record.administration.OperationLogRecord;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogRecord> {}
