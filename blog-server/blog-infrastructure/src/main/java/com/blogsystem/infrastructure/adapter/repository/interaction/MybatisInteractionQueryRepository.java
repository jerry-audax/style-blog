package com.blogsystem.infrastructure.adapter.repository.interaction;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.domain.interaction.query.CommentView;
import com.blogsystem.domain.interaction.query.ModerationCommentView;
import com.blogsystem.domain.interaction.adapter.repository.InteractionQueryRepository;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerAccountMapper;
import com.blogsystem.infrastructure.mybatis.mapper.interaction.CommentMapper;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;
import com.blogsystem.infrastructure.mybatis.record.identity.OwnerAccountRecord;
import com.blogsystem.infrastructure.mybatis.record.interaction.CommentRecord;
import com.blogsystem.types.model.PageResult;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only projections. Posting and moderation policies belong to domain/application.
 */
@Repository
@RequiredArgsConstructor
public class MybatisInteractionQueryRepository implements InteractionQueryRepository {
    private final CommentMapper commentMapper;
    private final OwnerAccountMapper sysUserMapper;
    private final ArticleMapper articleMapper;

    public List<CommentView> listByArticle(Long articleId) {
        ArticleRecord article = articleMapper.selectById(articleId);
        if (article == null
                || !Integer.valueOf(1).equals(article.getStatus())
                || !Integer.valueOf(0).equals(article.getDeleted())
                || !Integer.valueOf(1).equals(article.getIsCommentEnabled())) return List.of();
        List<CommentRecord> comments =
                commentMapper.selectList(
                        new LambdaQueryWrapper<CommentRecord>()
                                .eq(CommentRecord::getArticleId, articleId)
                                .eq(CommentRecord::getDeleted, 0)
                                .eq(CommentRecord::getStatus, 1)
                                .orderByAsc(CommentRecord::getId));

        Set<Long> userIds = new HashSet<>();
        for (CommentRecord c : comments) {
            userIds.add(c.getUserId());
            if (c.getReplyToUserId() != null && c.getReplyToUserId() > 0) {
                userIds.add(c.getReplyToUserId());
            }
        }
        List<OwnerAccountRecord> users =
                userIds.isEmpty() ? List.of() : sysUserMapper.selectBatchIds(userIds);
        Map<Long, String> nicknames =
                users.stream()
                        .collect(
                                Collectors.toMap(
                                        OwnerAccountRecord::getId,
                                        u ->
                                                u.getNickname() != null
                                                        ? u.getNickname()
                                                        : u.getUsername()));
        Map<Long, String> avatars =
                users.stream()
                        .filter(u -> u.getAvatar() != null)
                        .collect(
                                Collectors.toMap(
                                        OwnerAccountRecord::getId,
                                        OwnerAccountRecord::getAvatar,
                                        (a, b) -> a));

        return comments.stream()
                .map(
                        c ->
                                new CommentView(
                                        c.getId(),
                                        c.getArticleId(),
                                        c.getUserId(),
                                        nicknames.getOrDefault(c.getUserId(), "用户"),
                                        avatars.get(c.getUserId()),
                                        c.getParentId(),
                                        c.getReplyToUserId(),
                                        c.getReplyToUserId() != null
                                                ? nicknames.getOrDefault(c.getReplyToUserId(), "用户")
                                                : null,
                                        c.getContent(),
                                        c.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * 管理端评论分页查询，支持按状态筛选
     */
    public PageResult<ModerationCommentView> adminList(
            Integer status, long pageNum, long pageSize) {
        LambdaQueryWrapper<CommentRecord> wrapper =
                new LambdaQueryWrapper<CommentRecord>()
                        .eq(CommentRecord::getDeleted, 0)
                        .orderByDesc(CommentRecord::getId);
        if (status != null) {
            wrapper.eq(CommentRecord::getStatus, status);
        }
        Page<CommentRecord> page = commentMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return new PageResult<>(
                page.getRecords().stream()
                        .map(
                                c ->
                                        new ModerationCommentView(
                                                c.getId(),
                                                c.getArticleId(),
                                                c.getUserId(),
                                                c.getParentId(),
                                                c.getReplyToUserId(),
                                                c.getContent(),
                                                c.getStatus(),
                                                c.getIp(),
                                                c.getUserAgent(),
                                                c.getCreatedAt(),
                                                c.getUpdatedAt(),
                                                c.getDeleted()))
                        .toList(),
                page.getCurrent(),
                page.getSize(),
                page.getTotal());
    }
}
