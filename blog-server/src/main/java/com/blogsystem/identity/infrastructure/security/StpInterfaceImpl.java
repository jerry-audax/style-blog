package com.blogsystem.identity.infrastructure.security;

import cn.dev33.satoken.stp.StpInterface;
import com.blogsystem.identity.infrastructure.persistence.SysUserRole;
import com.blogsystem.identity.infrastructure.persistence.SysRoleMapper;
import com.blogsystem.identity.infrastructure.persistence.SysRolePermissionMapper;
import com.blogsystem.identity.infrastructure.persistence.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sa-Token 权限接口实现 —— 加载用户角色与权限列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.valueOf(String.valueOf(loginId));
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return Collections.emptyList();
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        List<String> perms = sysRolePermissionMapper.getPermissionCodesByRoleIds(roleIds);
        log.info("用户 {} 的角色IDs={}，权限码={}", loginId, roleIds, perms);
        return perms;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(String.valueOf(loginId));
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return Collections.emptyList();
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        return sysRoleMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.blogsystem.identity.infrastructure.persistence.SysRole>()
                                .in(com.blogsystem.identity.infrastructure.persistence.SysRole::getId, roleIds)
                                .eq(com.blogsystem.identity.infrastructure.persistence.SysRole::getDeleted, 0)
                                .eq(com.blogsystem.identity.infrastructure.persistence.SysRole::getStatus, 1))
                .stream().map(com.blogsystem.identity.infrastructure.persistence.SysRole::getRoleCode).collect(Collectors.toList());
    }
}
