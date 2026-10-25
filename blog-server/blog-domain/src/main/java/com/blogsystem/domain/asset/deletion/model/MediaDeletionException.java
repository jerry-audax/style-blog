package com.blogsystem.domain.asset.deletion.model;

/** Stable sanitized failures; never retain provider payloads or credentials. */
public final class MediaDeletionException extends RuntimeException {
    public enum Reason {
        INVALID_REQUEST,
        MISSING_VERSION,
        NOT_FOUND,
        CONFLICT,
        UNAVAILABLE,
        OPERATION_NOT_READY
    }

    private final Reason reason;

    public MediaDeletionException(Reason reason) {
        super(
                switch (reason) {
                    case INVALID_REQUEST -> "删除参数不正确";
                    case MISSING_VERSION -> "请先预览并提供资料版本";
                    case NOT_FOUND -> "未找到对应资料或操作";
                    case CONFLICT -> "资料或操作已变化，请核对原回执并重新预览";
                    case UNAVAILABLE -> "删除状态暂不可用，请保留原操作编号";
                    case OPERATION_NOT_READY -> "此删除操作尚未开放，图床文件未作处理";
                });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
