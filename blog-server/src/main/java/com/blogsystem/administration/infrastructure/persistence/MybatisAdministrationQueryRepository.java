package com.blogsystem.administration.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.identity.infrastructure.persistence.SysUser;
import com.blogsystem.identity.infrastructure.persistence.SysUserMapper;
import com.blogsystem.interaction.infrastructure.persistence.Comment;
import com.blogsystem.interaction.infrastructure.persistence.CommentMapper;
import com.blogsystem.content.infrastructure.persistence.Article;
import com.blogsystem.content.infrastructure.persistence.ArticleMapper;
import com.blogsystem.administration.domain.repository.AdministrationQueryRepository;
import com.blogsystem.administration.domain.query.*;
import com.blogsystem.content.infrastructure.persistence.ContentReadMapper;
import com.blogsystem.shared.domain.PageResult;
import com.blogsystem.shared.infrastructure.audit.entity.OperationLog;
import com.blogsystem.shared.infrastructure.audit.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 管理端只读投影 —— 仪表盘统计、操作日志
 */
@Repository
@RequiredArgsConstructor
public class MybatisAdministrationQueryRepository implements AdministrationQueryRepository {

    private final SysUserMapper sysUserMapper;
    private final ArticleMapper articleMapper;
    private final CommentMapper commentMapper;
    private final OperationLogMapper operationLogMapper;

    public long countUsers() {
        return sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getDeleted, 0));
    }

    /**
     * 仪表盘统计数据：文章/评论/用户数量 + 最近文章
     */
    public DashboardSummary getDashboardStats() {

        // 文章统计
        long totalArticles = articleMapper.selectCount(null);
        long publishedArticles = articleMapper.selectCount(
                new LambdaQueryWrapper<Article>()
                        .eq(Article::getStatus, 1)
                        .eq(Article::getDeleted, 0));

        // 评论统计
        long totalComments = commentMapper.selectCount(null);
        // No persisted moderation queue in the owner-only baseline (0=hidden, 1=visible).
        // Keep the legacy dashboard field without mislabelling visible/hidden comments as pending.
        long pendingComments = 0;

        // 用户统计
        long totalUsers = countUsers();

        // 最近 5 篇文章
        List<Article> recentArticles = articleMapper.selectList(
                new LambdaQueryWrapper<Article>()
                        .eq(Article::getDeleted, 0)
                        .orderByDesc(Article::getId)
                        .last("limit 5"));

        return new DashboardSummary(totalArticles, publishedArticles, totalComments, pendingComments,
                totalUsers, recentArticles.stream().map(ContentReadMapper::article).toList());
    }

    @Override
    public PageResult<OperationLogView> listLogs(String module, long pageNum, long pageSize) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>().orderByDesc(OperationLog::getId);
        if (module != null && !module.isEmpty()) wrapper.eq(OperationLog::getModule, module);
        Page<OperationLog> page = operationLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getRecords().stream().map(row -> new OperationLogView(row.getId(), row.getUserId(),
                row.getModule(), row.getAction(), row.getContent(), row.getRequestData(), row.getIp(),
                row.getUserAgent(), row.getCreatedAt())).toList(), page.getCurrent(), page.getSize(), page.getTotal());
    }

}
