package com.blogsystem.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.admin.service.AdminService;
import com.blogsystem.auth.entity.SysUser;
import com.blogsystem.common.ApiResponse;
import com.blogsystem.common.PageUtil;
import com.blogsystem.log.entity.OperationLog;
import com.blogsystem.log.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final OperationLogMapper operationLogMapper;

    @SaCheckPermission("admin:user:list")
    @GetMapping("/users")
    public ApiResponse<Page<SysUser>> users(@RequestParam(required = false) Integer status,
                                            @RequestParam(defaultValue = "1") Long pageNum,
                                            @RequestParam(defaultValue = "10") Long pageSize) {
        pageNum = PageUtil.clampPageNum(pageNum);
        pageSize = PageUtil.clampPageSize(pageSize);
        return ApiResponse.ok(adminService.listUsers(status, pageNum, pageSize));
    }

    @SaCheckPermission("admin:user:update")
    @PutMapping("/users/{id}/status")
    public ApiResponse<Map<String, Object>> toggleUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminService.toggleUserStatus(id, status);
        return ApiResponse.ok(Map.of("id", id, "status", status));
    }

    @SaCheckPermission("admin:user:delete")
    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return ApiResponse.ok();
    }

    @SaCheckLogin
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.ok(adminService.getDashboardStats());
    }

    @SaCheckPermission("admin:log:list")
    @GetMapping("/logs")
    public ApiResponse<Page<OperationLog>> logs(@RequestParam(required = false) String module,
                                                @RequestParam(defaultValue = "1") Long pageNum,
                                                @RequestParam(defaultValue = "10") Long pageSize) {
        pageNum = PageUtil.clampPageNum(pageNum);
        pageSize = PageUtil.clampPageSize(pageSize);
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getId);
        if (module != null && !module.isEmpty()) {
            wrapper.eq(OperationLog::getModule, module);
        }
        return ApiResponse.ok(operationLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
    }
}
