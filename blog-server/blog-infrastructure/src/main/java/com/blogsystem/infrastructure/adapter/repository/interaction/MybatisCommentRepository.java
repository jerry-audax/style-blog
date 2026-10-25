package com.blogsystem.infrastructure.adapter.repository.interaction;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blogsystem.domain.interaction.model.CommentOrigin;
import com.blogsystem.domain.interaction.model.CommentStatus;
import com.blogsystem.domain.interaction.model.CommentSubmission;
import com.blogsystem.domain.interaction.adapter.repository.CommentRepository;
import com.blogsystem.infrastructure.mybatis.mapper.interaction.CommentMapper;
import com.blogsystem.infrastructure.mybatis.record.interaction.CommentRecord;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Repository
public class MybatisCommentRepository implements CommentRepository {
    private final CommentMapper mapper;

    public MybatisCommentRepository(CommentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<com.blogsystem.domain.interaction.model.Comment> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id))
                .filter(row -> Integer.valueOf(0).equals(row.getDeleted()))
                .map(this::toDomain);
    }

    @Override
    public com.blogsystem.domain.interaction.model.Comment save(
            com.blogsystem.domain.interaction.model.Comment aggregate) {
        CommentRecord row = new CommentRecord();
        row.setId(aggregate.id());
        row.setArticleId(aggregate.articleId());
        row.setUserId(aggregate.authorId());
        row.setParentId(aggregate.parentId());
        row.setReplyToUserId(aggregate.replyToUserId());
        row.setContent(aggregate.content());
        row.setStatus(aggregate.status().persistedValue());
        row.setIp(aggregate.origin().ip());
        row.setUserAgent(aggregate.origin().userAgent());
        row.setDeleted(0);
        row.setCreatedAt(LocalDateTime.ofInstant(aggregate.createdAt(), ZoneId.systemDefault()));
        if (row.getId() == null) {
            if (mapper.insert(row) != 1 || row.getId() == null)
                throw new IllegalStateException("评论保存失败");
        } else {
            // Existing comments are immutable except for moderation status.
            if (mapper.update(
                            null,
                            new LambdaUpdateWrapper<CommentRecord>()
                                    .eq(CommentRecord::getId, row.getId())
                                    .eq(CommentRecord::getDeleted, 0)
                                    .set(CommentRecord::getStatus, row.getStatus()))
                    != 1) throw new IllegalArgumentException("评论不存在");
        }
        return toDomain(row);
    }

    @Override
    public void delete(Long id) {
        if (mapper.delete(
                        new LambdaQueryWrapper<CommentRecord>()
                                .eq(CommentRecord::getId, id)
                                .eq(CommentRecord::getDeleted, 0))
                != 1) throw new IllegalArgumentException("评论不存在");
    }

    private com.blogsystem.domain.interaction.model.Comment toDomain(CommentRecord row) {
        Instant created =
                row.getCreatedAt() == null
                        ? Instant.EPOCH
                        : row.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
        CommentStatus status = CommentStatus.forAudit(row.getStatus());
        return com.blogsystem.domain.interaction.model.Comment.reconstitute(
                row.getId(),
                new CommentSubmission(
                        row.getArticleId(),
                        row.getParentId(),
                        row.getReplyToUserId(),
                        row.getContent()),
                row.getUserId(),
                status,
                new CommentOrigin(row.getIp(), row.getUserAgent()),
                created);
    }
}
