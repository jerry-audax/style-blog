package com.blogsystem.domain.asset.upload.model.valobj;

import java.util.Objects;

/** Stable safe errors; never carries a SQL statement, provider body or credential. */
public final class MediaUploadException extends RuntimeException {
    public enum Reason {
        INVALID_REQUEST,
        TOO_LARGE,
        MISSING_VERSION,
        NOT_FOUND,
        CONFLICT,
        UNAVAILABLE
    }

    private final Reason reason;
    private final MediaUploadErrorCode code;

    public MediaUploadException(Reason reason) {
        this(reason, null);
    }

    public MediaUploadException(Reason reason, MediaUploadErrorCode code) {
        super(
                switch (Objects.requireNonNull(reason)) {
                    case INVALID_REQUEST -> "上传参数或内容无效";
                    case TOO_LARGE -> "文件超过此类资源的大小上限";
                    case MISSING_VERSION -> "请提供操作或资料版本";
                    case NOT_FOUND -> "上传操作或目标不存在";
                    case CONFLICT -> "操作或资料已变化，请先核对";
                    case UNAVAILABLE -> "上传状态暂不可用，请保留原操作编号";
                });
        this.reason = reason;
        this.code = code;
    }

    public Reason reason() {
        return reason;
    }

    public MediaUploadErrorCode code() {
        return code;
    }
}
