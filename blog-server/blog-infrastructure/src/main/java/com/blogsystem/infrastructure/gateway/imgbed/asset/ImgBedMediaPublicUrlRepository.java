package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Does not construct a provider client and has no access to its API token. */
public final class ImgBedMediaPublicUrlRepository implements MediaPublicUrlGateway {
    private final String base;
    private final String root;

    public ImgBedMediaPublicUrlRepository(URI baseUrl, String root) {
        boolean localHttp =
                baseUrl != null
                        && "http".equals(baseUrl.getScheme())
                        && "127.0.0.1".equals(baseUrl.getHost());
        if (baseUrl == null
                || (!"https".equals(baseUrl.getScheme()) && !localHttp)
                || baseUrl.getHost() == null
                || baseUrl.getRawUserInfo() != null
                || baseUrl.getRawQuery() != null
                || baseUrl.getRawFragment() != null
                || !baseUrl.getPath().matches("/?"))
            throw new IllegalArgumentException("Invalid media URL configuration");
        if (root == null || !root.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*"))
            throw new IllegalArgumentException("Invalid media root configuration");
        String original = baseUrl.toASCIIString();
        this.base =
                original.endsWith("/") ? original.substring(0, original.length() - 1) : original;
        this.root = root;
    }

    @Override
    public String publicUrl(String provider, MediaObjectKey key) {
        if (!"cloudflare_imgbed".equals(provider))
            throw new IllegalArgumentException("Unsupported media provider");
        key.requireWithin(root);
        String encoded =
                Arrays.stream(key.value().split("/", -1))
                        .map(
                                segment ->
                                        URLEncoder.encode(segment, StandardCharsets.UTF_8)
                                                .replace("+", "%20"))
                        .collect(Collectors.joining("/"));
        return base + "/file/" + encoded;
    }
}
