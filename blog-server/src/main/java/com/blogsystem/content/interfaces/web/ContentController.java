package com.blogsystem.content.interfaces.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.blogsystem.shared.interfaces.http.PageResponse;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import com.blogsystem.shared.application.PageUtil;
import com.blogsystem.content.interfaces.dto.ArticleSaveRequest;
import com.blogsystem.content.interfaces.dto.CategorySaveRequest;
import com.blogsystem.shared.application.annotation.OpLog;
import com.blogsystem.content.interfaces.dto.TagSaveRequest;
import com.blogsystem.content.domain.query.ArticleView;
import com.blogsystem.content.domain.query.CategoryView;
import com.blogsystem.content.domain.query.TagView;
import com.blogsystem.content.application.ContentApplicationFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 内容接口 —— 文章 / 分类 / 标签 的 CRUD
 */
@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class ContentController {

    private final ContentApplicationFacade contentService;

    /**
     * 新增 / 更新文章（需登录）
     */
    @SaCheckPermission("content:article:write")
    @OpLog(module = "文章管理", action = "新增/更新文章")
    @PostMapping("/article")
    public ApiResponse<Map<String, Long>> saveArticle(@RequestBody @Valid ArticleSaveRequest request) {
        return ApiResponse.ok(Map.of("id", contentService.saveArticle(request.toCommand())));
    }

    /**
     * 文章分页查询（公开端强制只返回已发布，管理端可传 status 筛选）
     */
    @GetMapping("/article/list")
    public ApiResponse<PageResponse<ArticleView>> listArticle(@RequestParam(required = false) Integer status,
                                                              @RequestParam(required = false) Long categoryId,
                                                              @RequestParam(required = false) Long tagId,
                                                              @RequestParam(defaultValue = "1") Long pageNum,
                                                              @RequestParam(defaultValue = "10") Long pageSize) {
        // 公开端：未登录或非管理员只能看已发布文章
        boolean isAdmin = false;
        try {
            isAdmin = cn.dev33.satoken.stp.StpUtil.hasPermission("admin:user:list");
        } catch (Exception ignored) {
        }
        if (!isAdmin) status = 1;
        pageNum = PageUtil.clampPageNum(pageNum);
        pageSize = PageUtil.clampPageSize(pageSize);
        return ApiResponse.ok(PageResponse.from(contentService.listArticles(status, categoryId, tagId, pageNum, pageSize)));
    }

    /**
     * 热门文章（沿用置顶/点赞排序）
     */
    @GetMapping("/article/hot")
    public ApiResponse<List<ArticleView>> listHotArticle(@RequestParam(defaultValue = "6") Long limit) {
        return ApiResponse.ok(contentService.listHotArticles(limit));
    }

    /**
     * 文章详情（公开端不可读取草稿，管理端可预览）
     */
    @GetMapping("/article/{id}")
    public ApiResponse<ArticleView> getArticle(@PathVariable Long id) {
        boolean isAdmin = false;
        try {
            isAdmin = cn.dev33.satoken.stp.StpUtil.hasPermission("admin:user:list");
        } catch (Exception ignored) {
        }
        return ApiResponse.ok(contentService.getArticle(id, isAdmin));
    }

    /**
     * 删除文章（需登录，软删除）
     */
    @SaCheckPermission("content:article:delete")
    @OpLog(module = "文章管理", action = "删除文章")
    @DeleteMapping("/article/{id}")
    public ApiResponse<Void> deleteArticle(@PathVariable Long id) {
        contentService.deleteArticle(id);
        return ApiResponse.ok();
    }

    /**
     * 新增 / 更新分类（需登录）
     */
    @SaCheckPermission("content:category:write")
    @OpLog(module = "分类管理", action = "新增/更新分类")
    @PostMapping("/category")
    public ApiResponse<Map<String, Long>> saveCategory(@RequestBody @Valid CategorySaveRequest request) {
        return ApiResponse.ok(Map.of("id", contentService.saveCategory(request.toDefinition())));
    }

    /**
     * 分类列表
     */
    @GetMapping("/category/list")
    public ApiResponse<List<CategoryView>> listCategory() {
        return ApiResponse.ok(contentService.listCategory());
    }

    /**
     * 删除分类（需登录，软删除）
     */
    @SaCheckPermission("content:category:delete")
    @OpLog(module = "分类管理", action = "删除分类")
    @DeleteMapping("/category/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        contentService.deleteCategory(id);
        return ApiResponse.ok();
    }

    /**
     * 新增 / 更新标签（需登录）
     */
    @SaCheckPermission("content:tag:write")
    @OpLog(module = "标签管理", action = "新增/更新标签")
    @PostMapping("/tag")
    public ApiResponse<Map<String, Long>> saveTag(@RequestBody @Valid TagSaveRequest request) {
        return ApiResponse.ok(Map.of("id", contentService.saveTag(request.toDefinition())));
    }

    /**
     * 标签列表
     */
    @GetMapping("/tag/list")
    public ApiResponse<List<TagView>> listTag() {
        return ApiResponse.ok(contentService.listTag());
    }

    /**
     * 删除标签（需登录，软删除）
     */
    @SaCheckPermission("content:tag:delete")
    @OpLog(module = "标签管理", action = "删除标签")
    @DeleteMapping("/tag/{id}")
    public ApiResponse<Void> deleteTag(@PathVariable Long id) {
        contentService.deleteTag(id);
        return ApiResponse.ok();
    }

    /**
     * 切换文章点赞（需登录）
     */
    @SaCheckLogin
    @PostMapping("/article/{id}/like")
    public ApiResponse<Map<String, Object>> toggleLike(@PathVariable Long id) {
        boolean liked = contentService.toggleLike(id);
        return ApiResponse.ok(Map.of("liked", liked));
    }
}
