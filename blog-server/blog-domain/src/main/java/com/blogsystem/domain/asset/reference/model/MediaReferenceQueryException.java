package com.blogsystem.domain.asset.reference.model;

/** Sanitized query failures; database/provider details never cross the domain boundary. */
public final class MediaReferenceQueryException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, UNAVAILABLE }

    private final Reason reason;

    public MediaReferenceQueryException(Reason reason) {
        super(switch (reason) {
            case INVALID_REQUEST -> "资源引用核对参数不正确";
            case UNAVAILABLE -> "资源引用核对暂不可用";
        });
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
