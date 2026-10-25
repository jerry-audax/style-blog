package com.blogsystem.trigger.http.asset;

public final class MediaPublicationRequestException extends RuntimeException {
    private final int status;
    public MediaPublicationRequestException(int status) { super("发布机器请求不正确"); this.status = status; }
    public int status() { return status; }
}
