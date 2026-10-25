package com.blogsystem.domain.interaction.model;

public enum CommentStatus {
    PENDING,
    VISIBLE,
    HIDDEN;

    public static CommentStatus forAudit(Integer status) {
        if (Integer.valueOf(1).equals(status)) return VISIBLE;
        // The existing admin/API uses 0=hidden; accept the old domain prototype's 2 as a
        // compatibility alias.
        if (Integer.valueOf(0).equals(status) || Integer.valueOf(2).equals(status)) return HIDDEN;
        throw new com.blogsystem.types.exception.DomainException("审核状态无效");
    }

    public int persistedValue() {
        if (this == VISIBLE) return 1;
        if (this == HIDDEN) return 0;
        throw new com.blogsystem.types.exception.DomainException("待审核状态尚无持久化协议");
    }
}
