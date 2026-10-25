package com.blogsystem.domain.asset.reference.model;

/** Sanitized write-protection failures; no source URL, object key or persistence exception. */
public final class MediaReferenceWriteException extends RuntimeException {
    public enum Reason { CONFLICT, UNAVAILABLE }

    private final Reason reason;

    public MediaReferenceWriteException(Reason reason) {
        super(switch (reason) {
            case CONFLICT -> "资源引用已变化或尚未登记，请先核对资源后重试";
            case UNAVAILABLE -> "资源引用保护暂不可用，请稍后重试";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
