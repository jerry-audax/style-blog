package com.blogsystem.comment.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.comment.dto.CommentSaveRequest;
import com.blogsystem.comment.dto.CommentVO;
import com.blogsystem.comment.entity.Comment;
import com.blogsystem.comment.service.CommentService;
import com.blogsystem.common.ApiResponse;
import com.blogsystem.common.PageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评论接口 —— 发表 / 文章评论列表 / 管理端审核
 */
@RestController
@RequestMapping("/api/comment")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * 发表评论（需登录）
     */
    @SaCheckLogin
    @PostMapping
    public ApiResponse<Map<String, Long>> save(@RequestBody @Valid CommentSaveRequest request,
                                               HttpServletRequest httpServletRequest) {
        Long id = commentService.save(request, httpServletRequest.getRemoteAddr(), httpServletRequest.getHeader("User-Agent"));
        return ApiResponse.ok(Map.of("id", id));
    }

    /**
     * 查询文章可见评论列表（含用户昵称和发表时间，游客不可见）
     */
    @GetMapping("/article/{articleId}")
    public ApiResponse<List<CommentVO>> listByArticle(@PathVariable Long articleId) {
        return ApiResponse.ok(commentService.listByArticle(articleId));
    }

    /**
     * 管理端评论分页查询，支持按状态筛选
     */
    @SaCheckPermission("comment:admin:list")
    @GetMapping("/admin/list")
    public ApiResponse<Page<Comment>> adminList(@RequestParam(required = false) Integer status,
                                                @RequestParam(defaultValue = "1") Long pageNum,
                                                @RequestParam(defaultValue = "10") Long pageSize) {
        pageNum = PageUtil.clampPageNum(pageNum);
        pageSize = PageUtil.clampPageSize(pageSize);
        return ApiResponse.ok(commentService.adminList(status, pageNum, pageSize));
    }

    /**
     * 管理端审核评论（通过 / 隐藏）
     */
    @SaCheckPermission("comment:admin:audit")
    @PostMapping("/admin/{id}/audit")
    public ApiResponse<Void> adminAudit(@PathVariable Long id, @RequestParam Integer status) {
        commentService.adminAudit(id, status);
        return ApiResponse.ok();
    }

    /**
     * 管理端删除评论（软删除）
     */
    @SaCheckPermission("comment:admin:delete")
    @DeleteMapping("/admin/{id}")
    public ApiResponse<Void> adminDelete(@PathVariable Long id) {
        commentService.adminDelete(id);
        return ApiResponse.ok();
    }
}
