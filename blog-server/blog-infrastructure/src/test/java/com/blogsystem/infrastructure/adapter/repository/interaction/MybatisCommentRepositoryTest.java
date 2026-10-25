package com.blogsystem.infrastructure.adapter.repository.interaction;

import com.blogsystem.infrastructure.adapter.repository.interaction.MybatisCommentRepository;
import com.blogsystem.infrastructure.mybatis.mapper.interaction.CommentMapper;
import com.blogsystem.infrastructure.mybatis.record.interaction.CommentRecord;

import org.assertj.core.api.Assertions;
import org.mockito.Mockito;

import com.blogsystem.domain.interaction.model.CommentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MybatisCommentRepositoryTest {
    @Test
    void deletedCommentsAreNotDomainAggregates() {
        var mapper = mock(CommentMapper.class);
        var row = new CommentRecord();
        row.setId(7L);
        row.setArticleId(1L);
        row.setUserId(2L);
        row.setContent("history");
        row.setStatus(1);
        row.setDeleted(1);
        when(mapper.selectById(7L)).thenReturn(row);
        assertThat(new MybatisCommentRepository(mapper).findById(7L)).isEmpty();
    }

    @Test
    void newCommentsReturnGeneratedIdentityAndState() {
        var mapper = mock(CommentMapper.class);
        when(mapper.insert(any(CommentRecord.class))).thenAnswer(call -> {
            CommentRecord row = call.getArgument(0);
            row.setId(99L);
            return 1;
        });
        var aggregate = com.blogsystem.domain.interaction.model.Comment.create(1L, 2L, "new", Instant.EPOCH);
        aggregate.audit(CommentStatus.VISIBLE);
        var saved = new MybatisCommentRepository(mapper).save(aggregate);
        assertThat(saved.id()).isEqualTo(99L);
        assertThat(saved.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(saved.createdAt()).isEqualTo(Instant.EPOCH);
    }
}
