package com.blogsystem.identity.domain.model;

/**
 * Immutable identity snapshot. Credentials must never appear in diagnostic output.
 */
public record OwnerAccount(long id, String phone, String username, String nickname, String email,
                           String avatar, String passwordHash, boolean active) {
    @Override
    public String toString() {
        return "OwnerAccount[credentials redacted]";
    }
}
