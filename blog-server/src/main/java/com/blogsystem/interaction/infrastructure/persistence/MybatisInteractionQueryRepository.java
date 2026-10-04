package com.blogsystem.interaction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.identity.infrastructure.persistence.SysUser;
import com.blogsystem.identity.infrastructure.persistence.SysUserMapper;
import com.blogsystem.content.infrastructure.persistence.Article;
import com.blogsystem.content.infrastructure.persistence.ArticleMapper;
import com.blogsystem.interaction.domain.query.*;
import com.blogsystem.interaction.domain.repository.InteractionQueryRepository;
import com.blogsystem.shared.domain.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only projections. Posting and moderation policies belong to domain/application.
 */
@Repository
@RequiredArgsConstructor
public class MybatisInteractionQueryRepository implements InteractionQueryRepository {
    private final CommentMapper commentMapper;
    private final SysUserMapper sysUserMapper;
    private final ArticleMapper articleMapper;

    public List<CommentView> listByArticle(Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null || !Integer.valueOf(1).equals(article.getStatus()) || !Integer.valueOf(0).equals(article.getDeleted())
                || !Integer.valueOf(1).equals(article.getIsCommentEnabled())) return List.of();
        List<Comment> comments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getDeleted, 0)
                .eq(Comment::getStatus, 1)
                .orderByAsc(Comment::getId));

        Set<Long> userIds = new HashSet<>();
        for (Comment c : comments) {
            userIds.add(c.getUserId());
            if (c.getReplyToUserId() != null && c.getReplyToUserId() > 0) {
                userIds.add(c.getReplyToUserId());
            }
        }
        List<SysUser> users = userIds.isEmpty() ? List.of() : sysUserMapper.selectBatchIds(userIds);
        Map<Long, String> nicknames = users.stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u.getNickname() != null ? u.getNickname() : u.getUsername()));
        Map<Long, String> avatars = users.stream()
                .filter(u -> u.getAvatar() != null)
                .collect(Collectors.toMap(SysUser::getId, SysUser::getAvatar, (a, b) -> a));

        return comments.stream().map(c -> new CommentView(
                c.getId(), c.getArticleId(), c.getUserId(),
                nicknames.getOrDefault(c.getUserId(), "用户"),
                avatars.get(c.getUserId()),
                c.getParentId(), c.getReplyToUserId(),
                c.getReplyToUserId() != null ? nicknames.getOrDefault(c.getReplyToUserId(), "用户") : null,
                c.getContent(), c.getCreatedAt()
        )).collect(Collectors.toList());
    }

    /**
     * 管理端评论分页查询，支持按状态筛选
     */
    public PageResult<ModerationCommentView> adminList(Integer status, long pageNum, long pageSize) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getDeleted, 0)
                .orderByDesc(Comment::getId);
        if (status != null) {
            wrapper.eq(Comment::getStatus, status);
        }
        Page<Comment> page = commentMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return new PageResult<>(page.getRecords().stream().map(c -> new ModerationCommentView(
                c.getId(), c.getArticleId(), c.getUserId(), c.getParentId(), c.getReplyToUserId(),
                c.getContent(), c.getStatus(), c.getIp(), c.getUserAgent(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getDeleted())).toList(), page.getCurrent(), page.getSize(), page.getTotal());
    }
}
