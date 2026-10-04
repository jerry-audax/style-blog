package com.blogsystem.administration.interfaces.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.blogsystem.shared.interfaces.http.PageResponse;
import com.blogsystem.administration.application.AdministrationApplicationFacade;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import com.blogsystem.shared.application.PageUtil;
import com.blogsystem.administration.domain.query.OperationLogView;
import com.blogsystem.administration.domain.query.DashboardSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdministrationApplicationFacade adminService;

    @SaCheckLogin
    @GetMapping("/dashboard")
    public ApiResponse<DashboardSummary> dashboard() {
        return ApiResponse.ok(adminService.dashboard());
    }

    @SaCheckPermission("admin:log:list")
    @GetMapping("/logs")
    public ApiResponse<PageResponse<OperationLogView>> logs(@RequestParam(required = false) String module,
                                                            @RequestParam(defaultValue = "1") Long pageNum, @RequestParam(defaultValue = "10") Long pageSize) {
        return ApiResponse.ok(PageResponse.from(adminService.logs(module, PageUtil.clampPageNum(pageNum), PageUtil.clampPageSize(pageSize))));
    }
}
