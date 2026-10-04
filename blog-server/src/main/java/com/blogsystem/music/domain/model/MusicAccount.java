package com.blogsystem.music.domain.model;

import java.time.Instant;

public final class MusicAccount {
    private MusicAccount() {
    }

    public record Status(boolean configured, boolean authorized) {
    }

    public record Authorization(boolean authorized, String authorizationUrl, Instant expiresAt) {
    }

    public record Revocation(boolean authorized, boolean remoteLogoutConfirmed) {
    }
}
