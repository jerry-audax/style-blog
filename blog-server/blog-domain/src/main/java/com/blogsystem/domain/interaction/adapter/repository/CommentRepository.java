package com.blogsystem.domain.interaction.adapter.repository;

import com.blogsystem.domain.interaction.model.Comment;

import java.util.Optional;

/**
 * Domain port for comment aggregate persistence.
 */
public interface CommentRepository {
    Optional<Comment> findById(Long id);

    Comment save(Comment comment);

    void delete(Long id);
}
