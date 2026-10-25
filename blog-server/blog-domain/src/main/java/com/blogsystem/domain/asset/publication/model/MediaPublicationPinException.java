package com.blogsystem.domain.asset.publication.model;

/** Stable failures without database, provider or credential details. */
public final class MediaPublicationPinException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, LIMIT_EXCEEDED, CONFLICT, NOT_FOUND, UNAVAILABLE }

    private final Reason reason;

    public MediaPublicationPinException(Reason reason) {
        super(switch (reason) {
            case INVALID_REQUEST -> "发布保护参数不正确";
            case LIMIT_EXCEEDED -> "发布保护请求超过限制";
            case CONFLICT -> "发布保护状态或清单已变化";
            case NOT_FOUND -> "未找到发布保护记录";
            case UNAVAILABLE -> "发布保护暂不可用";
        });
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
