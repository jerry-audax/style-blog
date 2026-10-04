package com.blogsystem.interaction.domain.repository;

import com.blogsystem.interaction.domain.model.CommentTarget;

import java.util.Optional;

/**
 * Anti-corruption read boundary to content; interaction never consumes an Article ORM record.
 */
public interface CommentTargetRepository {
    Optional<CommentTarget> findTarget(Long articleId);
}
