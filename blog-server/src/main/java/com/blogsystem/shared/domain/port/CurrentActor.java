package com.blogsystem.shared.domain.port;

/**
 * Authenticated identity supplied by the server, never by a request owner field.
 */
public interface CurrentActor {
    long requireUserId();
}

