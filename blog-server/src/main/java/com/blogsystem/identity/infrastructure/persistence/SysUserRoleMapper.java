package com.blogsystem.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.identity.infrastructure.persistence.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
