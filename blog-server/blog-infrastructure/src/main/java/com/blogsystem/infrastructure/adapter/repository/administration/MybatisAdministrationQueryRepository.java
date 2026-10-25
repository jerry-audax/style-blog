package com.blogsystem.infrastructure.adapter.repository.administration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.domain.administration.query.DashboardSummary;
import com.blogsystem.domain.administration.query.OperationLogView;
import com.blogsystem.domain.administration.adapter.repository.AdministrationQueryRepository;
import com.blogsystem.infrastructure.mybatis.converter.content.ContentReadConverter;
import com.blogsystem.infrastructure.mybatis.mapper.administration.OperationLogMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerAccountMapper;
import com.blogsystem.infrastructure.mybatis.mapper.interaction.CommentMapper;
import com.blogsystem.infrastructure.mybatis.record.administration.OperationLogRecord;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;
import com.blogsystem.infrastructure.mybatis.record.identity.OwnerAccountRecord;
import com.blogsystem.types.model.PageResult;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 管理端只读投影 —— 仪表盘统计、操作日志
 */
@Repository
@RequiredArgsConstructor
public class MybatisAdministrationQueryRepository implements AdministrationQueryRepository {

    private final OwnerAccountMapper sysUserMapper;
    private final ArticleMapper articleMapper;
    private final CommentMapper commentMapper;
    private final OperationLogMapper operationLogMapper;

    public long countUsers() {
        return sysUserMapper.selectCount(
                new LambdaQueryWrapper<OwnerAccountRecord>().eq(OwnerAccountRecord::getDeleted, 0));
    }

    /**
     * 仪表盘统计数据：文章/评论/用户数量 + 最近文章
     */
    public DashboardSummary getDashboardStats() {

        // 文章统计
        long totalArticles = articleMapper.selectCount(null);
        long publishedArticles =
                articleMapper.selectCount(
                        new LambdaQueryWrapper<ArticleRecord>()
                                .eq(ArticleRecord::getStatus, 1)
                                .eq(ArticleRecord::getDeleted, 0));

        // 评论统计
        long totalComments = commentMapper.selectCount(null);
        // No persisted moderation queue in the owner-only baseline (0=hidden, 1=visible).
        // Keep the legacy dashboard field without mislabelling visible/hidden comments as pending.
        long pendingComments = 0;

        // 用户统计
        long totalUsers = countUsers();

        // 最近 5 篇文章
        List<ArticleRecord> recentArticles =
                articleMapper.selectList(
                        new LambdaQueryWrapper<ArticleRecord>()
                                .eq(ArticleRecord::getDeleted, 0)
                                .orderByDesc(ArticleRecord::getId)
                                .last("limit 5"));

        return new DashboardSummary(
                totalArticles,
                publishedArticles,
                totalComments,
                pendingComments,
                totalUsers,
                recentArticles.stream().map(ContentReadConverter::article).toList());
    }

    @Override
    public PageResult<OperationLogView> listLogs(String module, long pageNum, long pageSize) {
        LambdaQueryWrapper<OperationLogRecord> wrapper =
                new LambdaQueryWrapper<OperationLogRecord>().orderByDesc(OperationLogRecord::getId);
        if (module != null && !module.isEmpty()) wrapper.eq(OperationLogRecord::getModule, module);
        Page<OperationLogRecord> page =
                operationLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return new PageResult<>(
                page.getRecords().stream()
                        .map(
                                row ->
                                        new OperationLogView(
                                                row.getId(),
                                                row.getUserId(),
                                                row.getModule(),
                                                row.getAction(),
                                                row.getContent(),
                                                row.getRequestData(),
                                                row.getIp(),
                                                row.getUserAgent(),
                                                row.getCreatedAt()))
                        .toList(),
                page.getCurrent(),
                page.getSize(),
                page.getTotal());
    }
}
