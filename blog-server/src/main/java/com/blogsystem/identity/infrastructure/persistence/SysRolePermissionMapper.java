package com.blogsystem.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsystem.identity.infrastructure.persistence.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    @Select("<script>" +
            "SELECT p.perm_code FROM sys_permission p " +
            "JOIN sys_role_permission rp ON p.id = rp.permission_id " +
            "WHERE rp.role_id IN <foreach collection='list' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<String> getPermissionCodesByRoleIds(List<Long> roleIds);

    /**
     * 确保角色拥有指定权限：先查 permission 是否存在，再关联到 role
     */
    @org.apache.ibatis.annotations.Insert("<script>" +
            "INSERT INTO sys_role_permission (role_id, permission_id) " +
            "SELECT #{roleId}, p.id FROM sys_permission p WHERE p.perm_code = #{permCode} " +
            "ON CONFLICT (role_id, permission_id) DO NOTHING" +
            "</script>")
    void ensureRolePermission(Long roleId, String permCode);
}
