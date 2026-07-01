package com.blogsystem.comment.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsystem.auth.entity.SysUser;
import com.blogsystem.auth.mapper.SysUserMapper;
import com.blogsystem.comment.dto.CommentSaveRequest;
import com.blogsystem.comment.dto.CommentVO;
import com.blogsystem.common.RedisFailurePolicy;
import com.blogsystem.comment.entity.Comment;
import com.blogsystem.comment.mapper.CommentMapper;
import com.blogsystem.content.entity.Article;
import com.blogsystem.content.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评论业务 —— 发表 / 文章评论列表 / 管理端审核
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final SysUserMapper sysUserMapper;
    private final ArticleMapper articleMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailurePolicy redisFailurePolicy;

    /**
     * 发表评论
     */
    public Long save(CommentSaveRequest request, String ip, String userAgent) {
        // 评论频率限制：每用户每分钟最多 3 条
        Long userId = StpUtil.getLoginIdAsLong();
        String rateKey = "rate:comment:user:" + userId;
        try {
            String count = redisTemplate.opsForValue().get(rateKey);
            if (count != null && Integer.parseInt(count) >= 3) {
                throw new IllegalArgumentException("评论过于频繁，请稍后再试");
            }
        } catch (IllegalArgumentException e) {
            throw e; // Business rejection — re-throw
        } catch (Exception e) {
            redisFailurePolicy.onSecurityReadFailure("comment rate check", rateKey, e);
        }
        // Record rate limit (best-effort)
        try {
            redisTemplate.opsForValue().increment(rateKey);
            redisTemplate.expire(rateKey, Duration.ofMinutes(1));
        } catch (Exception e) {
            redisFailurePolicy.onSecurityWriteFailure("comment rate set", rateKey, e);
        }

        // 校验文章是否存在、已发布、允许评论
        Article article = articleMapper.selectById(request.articleId());
        if (article == null) {
            throw new IllegalArgumentException("文章不存在");
        }
        if (article.getStatus() == null || article.getStatus() != 1) {
            throw new IllegalArgumentException("文章未发布，无法评论");
        }
        if (article.getIsCommentEnabled() == null || article.getIsCommentEnabled() != 1) {
            throw new IllegalArgumentException("文章已关闭评论");
        }

        // 如果是回复，校验父评论存在且属于同一文章
        Long parentId = request.parentId() == null ? 0L : request.parentId();
        if (parentId > 0) {
            Comment parent = commentMapper.selectById(parentId);
            if (parent == null || parent.getDeleted() == 1) {
                throw new IllegalArgumentException("父评论不存在");
            }
            if (!parent.getArticleId().equals(request.articleId())) {
                throw new IllegalArgumentException("父评论不属于该文章");
            }
        }

        Comment comment = new Comment();
        comment.setArticleId(request.articleId());
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setReplyToUserId(request.replyToUserId());
        comment.setContent(request.content());
        comment.setStatus(1);
        comment.setIp(ip);
        comment.setUserAgent(userAgent);
        comment.setDeleted(0);
        commentMapper.insert(comment);
        return comment.getId();
    }

    /**
     * 查询文章可见评论列表，附带用户昵称
     */
    public List<CommentVO> listByArticle(Long articleId) {
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

        return comments.stream().map(c -> new CommentVO(
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
    public Page<Comment> adminList(Integer status, long pageNum, long pageSize) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getDeleted, 0)
                .orderByDesc(Comment::getId);
        if (status != null) {
            wrapper.eq(Comment::getStatus, status);
        }
        return commentMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 管理端审核评论（通过/隐藏）
     */
    public void adminAudit(Long id, Integer status) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null || comment.getDeleted() == 1) {
            throw new IllegalArgumentException("评论不存在");
        }
        comment.setStatus(status);
        commentMapper.updateById(comment);
    }

    /**
     * 管理端删除评论（软删除）
     */
    public void adminDelete(Long id) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw new IllegalArgumentException("评论不存在");
        }
        commentMapper.deleteById(id);
    }
}
