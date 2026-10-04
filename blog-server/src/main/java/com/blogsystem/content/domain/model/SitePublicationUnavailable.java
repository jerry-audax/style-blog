package com.blogsystem.content.domain.model;

public class SitePublicationUnavailable extends RuntimeException {
    public SitePublicationUnavailable() {super("自动发布服务暂不可用，请稍后重试；后台内容已保存。");}
}
