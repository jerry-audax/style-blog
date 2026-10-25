package com.blogsystem.architecture;

import org.assertj.core.api.Assertions;

import com.blogsystem.domain.identity.model.PhoneNumber;
import com.blogsystem.domain.identity.model.User;
import com.blogsystem.domain.identity.model.UserId;
import com.blogsystem.domain.identity.model.UserStatus;
import com.blogsystem.domain.interaction.model.Comment;
import com.blogsystem.domain.interaction.model.CommentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ContextDomainModelTest {

    @Test
    void identityUserCanBeDisabledAndPhoneIsValidated() {
        var user = User.register(new UserId(1L), new PhoneNumber("13800000000"), Instant.parse("2026-09-09T00:00:00Z"));
        user.disable(Instant.parse("2026-09-09T01:00:00Z"));
        assertThat(user.status()).isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void commentStartsPendingAndCanBeAudited() {
        var comment = Comment.create(1L, 2L, "hello", Instant.parse("2026-09-09T00:00:00Z"));
        assertThat(comment.status()).isEqualTo(CommentStatus.PENDING);
        comment.audit(CommentStatus.VISIBLE);
        assertThat(comment.status()).isEqualTo(CommentStatus.VISIBLE);
    }

}
