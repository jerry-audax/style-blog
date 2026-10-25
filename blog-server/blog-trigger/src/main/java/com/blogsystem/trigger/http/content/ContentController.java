package com.blogsystem.trigger.http.content;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;

import com.blogsystem.application.content.service.ContentApplicationFacade;
import com.blogsystem.application.shared.support.PageUtil;
import com.blogsystem.domain.content.query.ArticleView;
import com.blogsystem.domain.content.query.CategoryView;
import com.blogsystem.domain.content.query.TagView;
import com.blogsystem.trigger.http.content.request.ArticleSaveRequest;
import com.blogsystem.trigger.http.content.request.CategorySaveRequest;
import com.blogsystem.trigger.http.content.request.TagSaveRequest;
import com.blogsystem.trigger.http.support.ApiResponse;
import com.blogsystem.trigger.http.support.PageResponse;
import com.blogsystem.types.audit.OpLog;

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
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

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
    @SaCheckRole("BLOG_OWNER")
    @OpLog(module = "文章管理", action = "新增/更新文章")
    @PostMapping("/article")
    public ApiResponse<Map<String, Long>> saveArticle(
            @RequestBody @Valid ArticleSaveRequest request) {
        return ApiResponse.ok(Map.of("id", contentService.saveArticle(request.toCommand())));
    }

    /**
     * 文章分页查询（公开端强制只返回已发布，管理端可传 status 筛选）
     */
    @GetMapping("/article/list")
    public ApiResponse<PageResponse<ArticleView>> listArticle(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long tagId,
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize) {
        // 公开端：未登录或非博主只能看已发布文章
        boolean isOwner = false;
        try {
            isOwner = cn.dev33.satoken.stp.StpUtil.hasRole("BLOG_OWNER");
        } catch (Exception ignored) {
        }
        if (!isOwner) status = 1;
        pageNum = PageUtil.clampPageNum(pageNum);
        pageSize = PageUtil.clampPageSize(pageSize);
        return ApiResponse.ok(
                PageResponse.from(
                        contentService.listArticles(status, categoryId, tagId, pageNum, pageSize)));
    }

    /**
     * 热门文章（沿用置顶/点赞排序）
     */
    @GetMapping("/article/hot")
    public ApiResponse<List<ArticleView>> listHotArticle(
            @RequestParam(defaultValue = "6") Long limit) {
        return ApiResponse.ok(contentService.listHotArticles(limit));
    }

    /**
     * 文章详情（公开端不可读取草稿，管理端可预览）
     */
    @GetMapping("/article/{id}")
    public ApiResponse<ArticleView> getArticle(@PathVariable Long id) {
        boolean isOwner = false;
        try {
            isOwner = cn.dev33.satoken.stp.StpUtil.hasRole("BLOG_OWNER");
        } catch (Exception ignored) {
        }
        return ApiResponse.ok(contentService.getArticle(id, isOwner));
    }

    /**
     * 删除文章（需登录，软删除）
     */
    @SaCheckRole("BLOG_OWNER")
    @OpLog(module = "文章管理", action = "删除文章")
    @DeleteMapping("/article/{id}")
    public ApiResponse<Void> deleteArticle(@PathVariable Long id) {
        contentService.deleteArticle(id);
        return ApiResponse.ok();
    }

    /**
     * 新增 / 更新分类（需登录）
     */
    @SaCheckRole("BLOG_OWNER")
    @OpLog(module = "分类管理", action = "新增/更新分类")
    @PostMapping("/category")
    public ApiResponse<Map<String, Long>> saveCategory(
            @RequestBody @Valid CategorySaveRequest request) {
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
    @SaCheckRole("BLOG_OWNER")
    @OpLog(module = "分类管理", action = "删除分类")
    @DeleteMapping("/category/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        contentService.deleteCategory(id);
        return ApiResponse.ok();
    }

    /**
     * 新增 / 更新标签（需登录）
     */
    @SaCheckRole("BLOG_OWNER")
    @OpLog(module = "标签管理", action = "新增/更新标签")
    @PostMapping("/tag")
    public ApiResponse<Map<String, Long>> saveTag(@RequestBody @Valid TagSaveRequest request) {
        return ApiResponse.ok(Map.of("id", contentService.saveTag(request.toDefinition())));
    }

    /**
     * 标签列表
     */
    @GetMapping("/tag/list")
    public ApiResponse<List<TagResponse>> listTag() {
        return ApiResponse.ok(contentService.listTag().stream().map(TagResponse::from).toList());
    }

    @GetMapping("/home-tags")
    public ResponseEntity<ApiResponse<HomeTagsResponse>> homeTags() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(HomeTagsResponse.from(contentService.listHomeTags())));
    }

    /**
     * 删除标签（需登录，软删除）
     */
    @SaCheckRole("BLOG_OWNER")
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
