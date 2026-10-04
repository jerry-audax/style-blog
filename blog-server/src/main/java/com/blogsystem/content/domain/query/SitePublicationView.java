package com.blogsystem.content.domain.query;

/** Public control-plane metadata only: never a filesystem path or worker credential. */
public record SitePublicationView(boolean configured, String phase, String publishedDigest,
                                  String desiredDigest, String publishedAt, String checkedAt, String error) {
    public static SitePublicationView unavailable() {
        return new SitePublicationView(false, "UNAVAILABLE", null, null, null, null,
                "自动发布服务未连接，请检查发布服务配置；后台内容已保存，不会丢失。");
    }
}
