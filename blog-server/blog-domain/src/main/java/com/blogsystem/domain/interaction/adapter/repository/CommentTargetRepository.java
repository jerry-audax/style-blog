package com.blogsystem.domain.interaction.adapter.repository;

import com.blogsystem.domain.interaction.model.CommentTarget;

import java.util.Optional;

/**
 * Anti-corruption read boundary to content; interaction never consumes an Article ORM record.
 */
public interface CommentTargetRepository {
    Optional<CommentTarget> findTarget(Long articleId);
}
