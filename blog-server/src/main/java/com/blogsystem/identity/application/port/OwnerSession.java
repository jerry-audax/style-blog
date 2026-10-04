package com.blogsystem.identity.application.port;

public interface OwnerSession {
    String login(long userId);

    long currentUserId();

    String token();

    boolean passwordAuthenticated();

    void logout();

    void revoke(long userId);
}
