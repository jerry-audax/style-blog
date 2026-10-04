package com.blogsystem.interaction.interfaces.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.blogsystem.shared.interfaces.http.PageResponse;
import com.blogsystem.interaction.interfaces.dto.CommentSaveRequest;
import com.blogsystem.interaction.domain.query.CommentView;
import com.blogsystem.interaction.domain.query.ModerationCommentView;
import com.blogsystem.interaction.application.InteractionApplicationFacade;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import com.blogsystem.shared.application.PageUtil;
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

    private final InteractionApplicationFacade commentService;

    /**
     * 发表评论（需登录）
     */
    @SaCheckLogin
    @PostMapping
    public ApiResponse<Map<String, Long>> save(@RequestBody @Valid CommentSaveRequest request,
                                               HttpServletRequest httpServletRequest) {
        Long id = commentService.save(request.toSubmission(), httpServletRequest.getRemoteAddr(), httpServletRequest.getHeader("User-Agent"));
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
    @SaCheckPermission("comment:admin:list")
    @GetMapping("/admin/list")
    public ApiResponse<PageResponse<ModerationCommentView>> adminList(@RequestParam(required = false) Integer status,
                                                                      @RequestParam(defaultValue = "1") Long pageNum,
                                                                      @RequestParam(defaultValue = "10") Long pageSize) {
        pageNum = PageUtil.clampPageNum(pageNum);
        pageSize = PageUtil.clampPageSize(pageSize);
        return ApiResponse.ok(PageResponse.from(commentService.adminList(status, pageNum, pageSize)));
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
