package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;

import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small multipart headers around a streaming source or one bounded chunk, never the whole source. */
final class MediaUploadMultipartBody {
    final String boundary = "BlogUpload" + UUID.randomUUID().toString().replace("-", "");
    final List<HttpRequest.BodyPublisher> parts = new ArrayList<>();

    void field(String name, String value) {
        parts.add(
                text(
                        "--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\""
                                + name
                                + "\"\r\n\r\n"
                                + value
                                + "\r\n"));
    }

    void file(String name, String mime, MediaUploadContent content) {
        header(name, mime);
        parts.add(
                HttpRequest.BodyPublishers.ofInputStream(
                        () -> {
                            try {
                                return content.openStream();
                            } catch (java.io.IOException e) {
                                throw new UncheckedIOException("Source unavailable", e);
                            }
                        }));
        parts.add(text("\r\n"));
    }

    void file(String name, String mime, byte[] bytes) {
        header(name, mime);
        parts.add(HttpRequest.BodyPublishers.ofByteArray(bytes));
        parts.add(text("\r\n"));
    }

    private void header(String name, String mime) {
        parts.add(
                text(
                        "--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                + name
                                + "\"\r\nContent-Type: "
                                + mime
                                + "\r\n\r\n"));
    }

    HttpRequest.BodyPublisher publisher() {
        parts.add(text("--" + boundary + "--\r\n"));
        return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
    }

    private static HttpRequest.BodyPublisher text(String text) {
        return HttpRequest.BodyPublishers.ofByteArray(text.getBytes(StandardCharsets.UTF_8));
    }
}
