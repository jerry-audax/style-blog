package com.blogsystem.asset.domain.model;

/**
 * Sanitized failures: never retain upstream messages, HTTP headers or exception causes.
 */
public final class ImageStorageException extends RuntimeException {
    public enum Reason {NOT_CONFIGURED, UNAVAILABLE, UNKNOWN_OUTCOME, BAD_RESPONSE}

    private final Reason reason;

    public ImageStorageException(Reason reason) {
        super(switch (reason) {
            case NOT_CONFIGURED -> "图床尚未配置，请联系管理员";
            case UNAVAILABLE -> "图床请求失败，请检查配置或稍后刷新";
            case UNKNOWN_OUTCOME -> "图床操作结果未确认，请先检查图片列表，不要立即重复上传或删除";
            case BAD_RESPONSE -> "图床响应无效，请联系管理员核对图片列表";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
