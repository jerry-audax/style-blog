package com.blogsystem.application.identity.result;

public record OwnerSessionView(
        Long userId,
        String phone,
        String username,
        String nickname,
        String email,
        String avatar,
        String token,
        boolean owner) {
    @Override
    public String toString() {
        return "OwnerSessionView[REDACTED]";
    }
}
