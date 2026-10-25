package com.blogsystem.domain.music.model;

/** Safe catalogue errors; deliberately carries no SQL or provider response. */
public final class MusicCatalogException extends RuntimeException {
    public enum Reason {
        NOT_FOUND,
        CONFLICT,
        DUPLICATE,
        MISSING_VERSION,
        INVALID_REQUEST,
        UNAVAILABLE,
        OPERATION_NOT_READY,
        TOO_LARGE
    }

    private final Reason reason;

    public MusicCatalogException(Reason reason) {
        super(
                switch (reason) {
                    case NOT_FOUND -> "歌曲不存在";
                    case CONFLICT -> "资料已变化，请刷新";
                    case DUPLICATE -> "歌曲 ID 已存在";
                    case MISSING_VERSION -> "请提供资料版本";
                    case INVALID_REQUEST -> "歌曲资料格式不正确";
                    case UNAVAILABLE -> "音乐目录暂不可用";
                    case OPERATION_NOT_READY -> "资源操作尚未接入";
                    case TOO_LARGE -> "公开歌单超过数量上限";
                });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
