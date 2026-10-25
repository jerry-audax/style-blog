package com.blogsystem.application.interaction.service;

import com.blogsystem.domain.interaction.model.Comment;
import com.blogsystem.domain.interaction.model.CommentOrigin;
import com.blogsystem.domain.interaction.model.CommentStatus;
import com.blogsystem.domain.interaction.model.CommentSubmission;
import com.blogsystem.domain.interaction.model.CommentTarget;
import com.blogsystem.domain.interaction.adapter.repository.CommentRepository;
import com.blogsystem.domain.interaction.adapter.repository.CommentTargetRepository;
import com.blogsystem.domain.interaction.adapter.repository.InteractionQueryRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.blogsystem.domain.interaction.gateway.CommentRateLimit;
import com.blogsystem.types.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InteractionApplicationFacadeTest {
    CommentRepository comments;
    CommentTargetRepository targets;
    CommentRateLimit limit;
    InteractionApplicationFacade service;

    @BeforeEach
    void setup() {
        comments = mock(CommentRepository.class);
        targets = mock(CommentTargetRepository.class);
        limit = mock(CommentRateLimit.class);
        service = new InteractionApplicationFacade(comments, mock(InteractionQueryRepository.class), targets, limit,
                () -> 42L, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    Comment existing(Long id, Long articleId) {
        return Comment.reconstitute(id, articleId, 42L, "history", CommentStatus.PENDING, Instant.EPOCH);
    }

    @Test
    void moderationUsesAggregateAndPersistsItsIdentity() {
        when(comments.findById(1L)).thenReturn(Optional.of(existing(1L, 2L)));
        service.adminAudit(1L, 0);
        verify(comments).save(argThat(c -> c.id().equals(1L) && c.status() == CommentStatus.HIDDEN));
    }

    @Test
    void invalidAuditStateIsRejectedBeforePersistence() {
        for (Integer status : new Integer[]{null, 3, -1})
            assertThatThrownBy(() -> service.adminAudit(1L, status)).isInstanceOf(DomainException.class);
        verifyNoInteractions(comments);
    }

    @Test
    void missingCommentIsNotCreatedByAudit() {
        when(comments.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.adminAudit(99L, 1)).isInstanceOf(IllegalArgumentException.class);
        verify(comments, never()).save(any());
    }

    @Test
    void sameModerationDecisionIsSafeToRepeat() {
        Comment comment = existing(1L, 2L);
        when(comments.findById(1L)).thenReturn(Optional.of(comment));
        service.adminAudit(1L, 1);
        service.adminAudit(1L, 1);
        assertThat(comment.status()).isEqualTo(CommentStatus.VISIBLE);
        verify(comments, times(2)).save(comment);
    }

    @Test
    void ownerPostingUsesGeneratedIdAndAuditContext() {
        when(targets.findTarget(2L)).thenReturn(Optional.of(new CommentTarget(2L, true, true)));
        when(comments.save(any())).thenAnswer(call -> {
            Comment comment = call.getArgument(0);
            assertThat(comment.authorId()).isEqualTo(42L);
            assertThat(comment.origin()).isEqualTo(new CommentOrigin("ip", "ua"));
            assertThat(comment.status()).isEqualTo(CommentStatus.VISIBLE);
            return Comment.reconstitute(7L, new CommentSubmission(comment.articleId(), comment.parentId(), comment.replyToUserId(), comment.content()),
                    comment.authorId(), comment.status(), comment.origin(), comment.createdAt());
        });
        assertThat(service.save(new CommentSubmission(2L, null, null, "reply"), "ip", "ua")).isEqualTo(7L);
        verify(limit).requireAllowed(42L);
    }

    @Test
    void replyCannotCrossArticleBoundary() {
        when(targets.findTarget(2L)).thenReturn(Optional.of(new CommentTarget(2L, true, true)));
        when(comments.findById(5L)).thenReturn(Optional.of(existing(5L, 3L)));
        assertThatThrownBy(() -> service.save(new CommentSubmission(2L, 5L, null, "reply"), "ip", "ua")).isInstanceOf(DomainException.class);
        verify(comments, never()).save(any());
    }

    @Test
    void closedOrDraftArticlesCannotReceiveComments() {
        for (CommentTarget target : new CommentTarget[]{new CommentTarget(2L, false, true), new CommentTarget(2L, true, false)}) {
            when(targets.findTarget(2L)).thenReturn(Optional.of(target));
            assertThatThrownBy(() -> service.save(new CommentSubmission(2L, null, null, "reply"), "ip", "ua")).isInstanceOf(DomainException.class);
        }
        verify(comments, never()).save(any());
    }

    @Test
    void rateLimitFailureDoesNotWriteCommentOrQueryTarget() {
        doThrow(new DomainException("limited")).when(limit).requireAllowed(42L);
        assertThatThrownBy(() -> service.save(new CommentSubmission(2L, null, null, "reply"), "ip", "ua")).isInstanceOf(DomainException.class);
        verifyNoInteractions(comments, targets);
    }
}
