package com.blogsystem.trigger.http.interaction;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;

import com.blogsystem.application.interaction.service.InteractionApplicationFacade;
import com.blogsystem.application.shared.support.PageUtil;
import com.blogsystem.domain.interaction.query.CommentView;
import com.blogsystem.domain.interaction.query.ModerationCommentView;
import com.blogsystem.trigger.http.interaction.request.CommentSaveRequest;
import com.blogsystem.trigger.http.support.ApiResponse;
import com.blogsystem.trigger.http.support.PageResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 评论接口 —— 发表 / 文章评论列表 / 管理端审核
 */
@RestController
@RequestMapping("/api/comment")
@RequiredArgsConstructor
public class CommentController {

    private final InteractionApplicationFacade commentService;

    /**
     * 发表评论（需登录）
     */
    @SaCheckLogin
    @PostMapping
    public ApiResponse<Map<String, Long>> save(
            @RequestBody @Valid CommentSaveRequest request, HttpServletRequest httpServletRequest) {
        Long id =
                commentService.save(
                        request.toSubmission(),
                        httpServletRequest.getRemoteAddr(),
                        httpServletRequest.getHeader("User-Agent"));
        return ApiResponse.ok(Map.of("id", id));
    }

    /**
     * 查询已发布、开放评论文章的已审核历史评论；公开端只读。
     */
    @GetMapping("/article/{articleId}")
    public ApiResponse<List<CommentView>> listByArticle(@PathVariable Long articleId) {
        return ApiResponse.ok(commentService.listByArticle(articleId));
    }

    /**
     * 管理端评论分页查询，支持按状态筛选
     */
    @SaCheckRole("BLOG_OWNER")
    @GetMapping("/admin/list")
    public ApiResponse<PageResponse<ModerationCommentView>> adminList(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize) {
        pageNum = PageUtil.clampPageNum(pageNum);
        pageSize = PageUtil.clampPageSize(pageSize);
        return ApiResponse.ok(
                PageResponse.from(commentService.adminList(status, pageNum, pageSize)));
    }

    /**
     * 管理端审核评论（通过 / 隐藏）
     */
    @SaCheckRole("BLOG_OWNER")
    @PostMapping("/admin/{id}/audit")
    public ApiResponse<Void> adminAudit(@PathVariable Long id, @RequestParam Integer status) {
        commentService.adminAudit(id, status);
        return ApiResponse.ok();
    }

    /**
     * 管理端删除评论（软删除）
     */
    @SaCheckRole("BLOG_OWNER")
    @DeleteMapping("/admin/{id}")
    public ApiResponse<Void> adminDelete(@PathVariable Long id) {
        commentService.adminDelete(id);
        return ApiResponse.ok();
    }
}
