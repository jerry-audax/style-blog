package com.blogsystem.domain.interaction.model;

/**
 * Audit context, not an HTTP request.
 */
public record CommentOrigin(String ip, String userAgent) {}
