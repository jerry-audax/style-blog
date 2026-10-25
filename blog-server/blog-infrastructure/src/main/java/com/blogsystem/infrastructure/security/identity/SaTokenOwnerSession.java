package com.blogsystem.infrastructure.security.identity;

import cn.dev33.satoken.stp.StpUtil;

import com.blogsystem.domain.identity.gateway.OwnerSession;

import org.springframework.stereotype.Component;

@Component
public class SaTokenOwnerSession implements OwnerSession {
    public static final String PASSWORD_SESSION_MARKER = "single-owner-password-v1";

    @Override
    public String login(long userId) {
        try {
            StpUtil.login(userId);
            StpUtil.getTokenSession().set(PASSWORD_SESSION_MARKER, true);
            return StpUtil.getTokenValue();
        } catch (RuntimeException unavailable) {
            try {
                StpUtil.logout();
            } catch (RuntimeException ignored) {
                /* Never mark a partial session as authenticated. */
            }
            throw new com.blogsystem.domain.identity.model.OwnerAccessException(
                    com.blogsystem.domain.identity.model.OwnerAccessException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    @Override
    public String token() {
        return StpUtil.getTokenValue();
    }

    @Override
    public boolean passwordAuthenticated() {
        try {
            return Boolean.TRUE.equals(StpUtil.getTokenSession().get(PASSWORD_SESSION_MARKER));
        } catch (RuntimeException unavailable) {
            throw new com.blogsystem.domain.identity.model.OwnerAccessException(
                    com.blogsystem.domain.identity.model.OwnerAccessException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public void revoke(long userId) {
        StpUtil.logout(userId);
    }
}
