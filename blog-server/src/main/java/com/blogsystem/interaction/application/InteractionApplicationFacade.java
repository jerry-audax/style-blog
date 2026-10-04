package com.blogsystem.interaction.application;

import com.blogsystem.interaction.domain.model.*;
import com.blogsystem.interaction.domain.repository.*;
import com.blogsystem.interaction.domain.port.CommentRateLimit;
import com.blogsystem.interaction.domain.query.*;
import com.blogsystem.shared.domain.PageResult;
import com.blogsystem.shared.domain.port.CurrentActor;
import com.blogsystem.shared.application.PageUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class InteractionApplicationFacade {
    private final CommentRepository comments;
    private final InteractionQueryRepository queries;
    private final CommentTargetRepository targets;
    private final CommentRateLimit limit;
    private final CurrentActor actor;
    private final Clock clock;

    public InteractionApplicationFacade(CommentRepository comments, InteractionQueryRepository queries,
                                        CommentTargetRepository targets, CommentRateLimit limit, CurrentActor actor, Clock clock) {
        this.comments = comments;
        this.queries = queries;
        this.targets = targets;
        this.limit = limit;
        this.actor = actor;
        this.clock = clock;
    }

    @Transactional
    public Long save(CommentSubmission submission, String ip, String userAgent) {
        long userId = actor.requireUserId();
        limit.requireAllowed(userId);
        targets.findTarget(submission.articleId()).orElseThrow(() -> new IllegalArgumentException("文章不存在"))
                .requireCommentable();
        Comment comment = Comment.submit(submission, userId, new CommentOrigin(ip, userAgent), clock.instant());
        if (submission.parentId() > 0) {
            Comment parent = comments.findById(submission.parentId()).orElseThrow(() -> new IllegalArgumentException("父评论不存在"));
            comment.requireValidParent(parent);
        }
        // Only the configured owner can submit. Preserve the existing owner-post visibility.
        comment.audit(CommentStatus.VISIBLE);
        return comments.save(comment).id();
    }

    public List<CommentView> listByArticle(Long articleId) {
        return queries.listByArticle(articleId);
    }

    public PageResult<ModerationCommentView> adminList(Integer status, long pageNum, long pageSize) {
        return queries.adminList(status, PageUtil.clampPageNum(pageNum), PageUtil.clampPageSize(pageSize));
    }

    @Transactional
    public void adminAudit(Long id, Integer status) {
        CommentStatus target = CommentStatus.forAudit(status);
        Comment comment = comments.findById(id).orElseThrow(() -> new IllegalArgumentException("评论不存在"));
        comment.audit(target);
        comments.save(comment);
    }

    @Transactional
    public void adminDelete(Long id) {
        comments.delete(id);
    }
}
