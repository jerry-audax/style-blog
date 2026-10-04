package com.blogsystem.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.identity.infrastructure.persistence.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
