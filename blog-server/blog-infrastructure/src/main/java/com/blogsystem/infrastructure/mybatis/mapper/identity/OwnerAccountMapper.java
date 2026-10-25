package com.blogsystem.infrastructure.mybatis.mapper.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.infrastructure.mybatis.record.identity.OwnerAccountRecord;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OwnerAccountMapper extends BaseMapper<OwnerAccountRecord> {
    @Select("SELECT * FROM sys_user WHERE id=#{id} AND status=1 AND deleted=0 FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    OwnerAccountRecord selectActiveForUpdate(@Param("id") long id);
}
