package com.blogsystem.music.domain.model;

public class MusicException extends RuntimeException {
    public enum Reason {NOT_CONFIGURED, NOT_AUTHORIZED, UNAVAILABLE, BUSY, INVALID_RESPONSE, INVALID_REQUEST,
        TRACK_NOT_FOUND, PLAYLIST_NOT_FOUND, PLAYLIST_TOO_LARGE}

    private final Reason reason;

    public MusicException(Reason reason) {
        super(switch (reason) {
            case NOT_CONFIGURED -> "音乐服务尚未配置，请在后端配置官方 CLI";
            case NOT_AUTHORIZED -> "网易云授权已失效，请重新授权";
            case BUSY -> "已有音乐操作进行中，请稍后检查状态";
            case PLAYLIST_NOT_FOUND -> "授权账号未创建或收藏指定歌单，请检查歌单和授权账号";
            case PLAYLIST_TOO_LARGE -> "歌单超过 2000 首，本次未替换已同步的歌单";
            case INVALID_REQUEST -> "音乐资料或文件类型不符合要求";
            case TRACK_NOT_FOUND -> "歌曲不存在，请先刷新音乐目录";
            default -> "网易云服务暂时不可用，请稍后重试；已同步歌单保持不变";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
