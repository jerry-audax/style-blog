package com.blogsystem.domain.interaction;

import org.assertj.core.api.Assertions;

import com.blogsystem.domain.interaction.model.CommentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommentStatusContractTest {
    @Test
    void existingAdminHideActionStillUsesZero() {
        assertThat(CommentStatus.forAudit(0)).isEqualTo(CommentStatus.HIDDEN);
        assertThat(CommentStatus.forAudit(1)).isEqualTo(CommentStatus.VISIBLE);
    }
}
