package com.blogsystem.interaction.domain.repository;

import com.blogsystem.interaction.domain.model.Comment;

import java.util.Optional;

/**
 * Domain port for comment aggregate persistence.
 */
public interface CommentRepository {
    Optional<Comment> findById(Long id);

    Comment save(Comment comment);

    void delete(Long id);
}
