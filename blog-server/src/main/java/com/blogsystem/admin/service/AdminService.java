package com.blogsystem.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.auth.entity.SysUser;
import com.blogsystem.auth.mapper.SysUserMapper;
import com.blogsystem.comment.entity.Comment;
import com.blogsystem.comment.mapper.CommentMapper;
import com.blogsystem.content.entity.Article;
import com.blogsystem.content.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端业务 —— 用户管理、仪表盘统计
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private final SysUserMapper sysUserMapper;
    private final ArticleMapper articleMapper;
    private final CommentMapper commentMapper;

    /**
     * 分页查询用户列表，支持按状态筛选
     */
    public Page<SysUser> listUsers(Integer status, long pageNum, long pageSize) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .orderByDesc(SysUser::getId);
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }
        return sysUserMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 切换用户启用/禁用状态
     */
    public void toggleUserStatus(Long id, Integer status) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null || user.getDeleted() == 1) {
            throw new IllegalArgumentException("用户不存在");
        }
        user.setStatus(status);
        sysUserMapper.updateById(user);
    }

    public long countUsers() {
        return sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getDeleted, 0));
    }

    /**
     * 仪表盘统计数据：文章/评论/用户数量 + 最近文章
     */
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 文章统计
        long totalArticles = articleMapper.selectCount(null);
        long publishedArticles = articleMapper.selectCount(
                new LambdaQueryWrapper<Article>()
                        .eq(Article::getStatus, 1)
                        .eq(Article::getDeleted, 0));

        // 评论统计
        long totalComments = commentMapper.selectCount(null);
        long pendingComments = commentMapper.selectCount(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getStatus, 1));

        // 用户统计
        long totalUsers = countUsers();

        // 最近 5 篇文章
        List<Article> recentArticles = articleMapper.selectList(
                new LambdaQueryWrapper<Article>()
                        .eq(Article::getDeleted, 0)
                        .orderByDesc(Article::getId)
                        .last("limit 5"));

        data.put("totalArticles", totalArticles);
        data.put("publishedArticles", publishedArticles);
        data.put("totalComments", totalComments);
        data.put("pendingComments", pendingComments);
        data.put("totalUsers", totalUsers);
        data.put("recentArticles", recentArticles);

        return data;
    }

    /**
     * 软删除用户
     */
    public void deleteUser(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        sysUserMapper.deleteById(id);
    }
}
