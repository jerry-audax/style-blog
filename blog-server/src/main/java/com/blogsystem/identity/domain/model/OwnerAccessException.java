package com.blogsystem.identity.domain.model;

public final class OwnerAccessException extends RuntimeException {
    public enum Reason {INVALID_CREDENTIALS, PASSWORD_REQUIRED, OWNER_ONLY, RATE_LIMITED, UNAVAILABLE}

    private final Reason reason;

    public OwnerAccessException(Reason reason) {
        super(switch (reason) {
            case INVALID_CREDENTIALS -> "账号或密码错误";
            case PASSWORD_REQUIRED -> "登录凭证已更新，请使用博主密码重新登录";
            case OWNER_ONLY -> "仅博主账号可访问管理功能";
            case RATE_LIMITED -> "登录尝试过于频繁，请稍后再试";
            case UNAVAILABLE -> "认证服务暂时不可用，请稍后再试";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
