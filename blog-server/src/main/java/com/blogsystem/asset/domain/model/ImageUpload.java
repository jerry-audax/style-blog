package com.blogsystem.asset.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Immutable validated image; no HTTP, multipart or provider objects enter the domain.
 */
public final class ImageUpload {
    private final byte[] bytes;
    private final String mediaType;
    private final ImagePurpose purpose;
    private final String extension;

    public ImageUpload(byte[] bytes, String mediaType, ImagePurpose purpose) {
        if (purpose == null || bytes == null || bytes.length == 0) throw new IllegalArgumentException("请选择图片");
        int limit = (purpose == ImagePurpose.AVATAR ? 2 : 10) * 1024 * 1024;
        if (bytes.length > limit) throw new IllegalArgumentException("图片超过大小限制（头像 2 MiB，其他 10 MiB）");
        String detected = detect(bytes);
        if (detected == null || !detected.equals(mediaType))
            throw new IllegalArgumentException("仅支持真实的 JPEG、PNG、GIF、WebP 图片，文件类型必须匹配");
        this.bytes = bytes.clone();
        this.mediaType = mediaType;
        this.purpose = purpose;
        this.extension = switch (mediaType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            default -> "webp";
        };
    }

    private static String detect(byte[] b) {
        if (starts(b, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return "image/png";
        if (starts(b, new byte[]{(byte) 255, (byte) 216, (byte) 255})) return "image/jpeg";
        if (starts(b, "GIF87a".getBytes(StandardCharsets.US_ASCII)) || starts(b, "GIF89a".getBytes(StandardCharsets.US_ASCII)))
            return "image/gif";
        if (b.length >= 12 && starts(b, "RIFF".getBytes(StandardCharsets.US_ASCII)) &&
                Arrays.equals(Arrays.copyOfRange(b, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII)))
            return "image/webp";
        return null;
    }

    private static boolean starts(byte[] b, byte[] prefix) {
        return b.length >= prefix.length && Arrays.equals(Arrays.copyOf(b, prefix.length), prefix);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    public String mediaType() {
        return mediaType;
    }

    public ImagePurpose purpose() {
        return purpose;
    }

    public String extension() {
        return extension;
    }
}
