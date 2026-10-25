package com.blogsystem.trigger.http.administration;

import cn.dev33.satoken.annotation.SaCheckRole;

import com.blogsystem.application.administration.service.AdministrationApplicationFacade;
import com.blogsystem.application.shared.support.PageUtil;
import com.blogsystem.domain.administration.query.DashboardSummary;
import com.blogsystem.domain.administration.query.OperationLogView;
import com.blogsystem.trigger.http.support.ApiResponse;
import com.blogsystem.trigger.http.support.PageResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdministrationApplicationFacade adminService;

    @SaCheckRole("BLOG_OWNER")
    @GetMapping("/dashboard")
    public ApiResponse<DashboardSummary> dashboard() {
        return ApiResponse.ok(adminService.dashboard());
    }

    @SaCheckRole("BLOG_OWNER")
    @GetMapping("/logs")
    public ApiResponse<PageResponse<OperationLogView>> logs(
            @RequestParam(required = false) String module,
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize) {
        return ApiResponse.ok(
                PageResponse.from(
                        adminService.logs(
                                module,
                                PageUtil.clampPageNum(pageNum),
                                PageUtil.clampPageSize(pageSize))));
    }
}
