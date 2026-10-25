package com.blogsystem.domain.surprise.model;

public final class SurpriseCatalogException extends RuntimeException {
    public enum Reason {
        NOT_FOUND,
        CONFLICT,
        MISSING_VERSION,
        INVALID_REQUEST,
        UNAVAILABLE,
        OPERATION_NOT_READY
    }

    private final Reason reason;

    public SurpriseCatalogException(Reason reason) {
        super(
                switch (reason) {
                    case NOT_FOUND -> "视频不存在";
                    case CONFLICT -> "资料已变化，请刷新";
                    case MISSING_VERSION -> "请提供资料版本";
                    case INVALID_REQUEST -> "视频资料格式不正确";
                    case UNAVAILABLE -> "惊喜视频暂不可用";
                    case OPERATION_NOT_READY -> "资源操作尚未接入";
                });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
