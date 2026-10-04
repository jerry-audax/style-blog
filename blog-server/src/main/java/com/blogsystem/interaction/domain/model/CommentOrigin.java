package com.blogsystem.interaction.domain.model;

/**
 * Audit context, not an HTTP request.
 */
public record CommentOrigin(String ip, String userAgent) {
}
