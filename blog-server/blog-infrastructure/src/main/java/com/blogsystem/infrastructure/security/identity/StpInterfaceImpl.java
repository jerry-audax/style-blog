package com.blogsystem.infrastructure.security.identity;

import cn.dev33.satoken.stp.StpInterface;

import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerAccountRoleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerRoleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerRolePermissionMapper;
import com.blogsystem.infrastructure.mybatis.record.identity.OwnerAccountRoleRecord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

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

    private final OwnerAccountRoleMapper sysUserRoleMapper;
    private final OwnerRoleMapper sysRoleMapper;
    private final OwnerRolePermissionMapper sysRolePermissionMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.valueOf(String.valueOf(loginId));
        List<OwnerAccountRoleRecord> userRoles =
                sysUserRoleMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                        OwnerAccountRoleRecord>()
                                .eq(OwnerAccountRoleRecord::getUserId, userId));
        if (userRoles.isEmpty()) return Collections.emptyList();
        List<Long> roleIds = userRoles.stream().map(OwnerAccountRoleRecord::getRoleId).toList();
        List<String> perms = sysRolePermissionMapper.getPermissionCodesByRoleIds(roleIds);
        log.info("用户 {} 的角色IDs={}，权限码={}", loginId, roleIds, perms);
        return perms;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(String.valueOf(loginId));
        List<OwnerAccountRoleRecord> userRoles =
                sysUserRoleMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                        OwnerAccountRoleRecord>()
                                .eq(OwnerAccountRoleRecord::getUserId, userId));
        if (userRoles.isEmpty()) return Collections.emptyList();
        List<Long> roleIds = userRoles.stream().map(OwnerAccountRoleRecord::getRoleId).toList();
        return sysRoleMapper
                .selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                        com.blogsystem.infrastructure.mybatis.record.identity
                                                .OwnerRoleRecord>()
                                .in(
                                        com.blogsystem.infrastructure.mybatis.record.identity
                                                        .OwnerRoleRecord
                                                ::getId,
                                        roleIds)
                                .eq(
                                        com.blogsystem.infrastructure.mybatis.record.identity
                                                        .OwnerRoleRecord
                                                ::getDeleted,
                                        0)
                                .eq(
                                        com.blogsystem.infrastructure.mybatis.record.identity
                                                        .OwnerRoleRecord
                                                ::getStatus,
                                        1))
                .stream()
                .map(
                        com.blogsystem.infrastructure.mybatis.record.identity.OwnerRoleRecord
                                ::getRoleCode)
                .collect(Collectors.toList());
    }
}
