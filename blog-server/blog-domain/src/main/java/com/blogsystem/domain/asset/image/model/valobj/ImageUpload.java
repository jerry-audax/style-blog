package com.blogsystem.domain.asset.image.model.valobj;

import com.blogsystem.domain.asset.image.policy.ImageSignaturePolicy;

/**
 * Immutable validated image; no HTTP, multipart or provider objects enter the domain.
 */
public final class ImageUpload {
    private final byte[] bytes;
    private final String mediaType;
    private final ImagePurpose purpose;
    private final String extension;

    public ImageUpload(byte[] bytes, String mediaType, ImagePurpose purpose) {
        if (purpose == null || bytes == null || bytes.length == 0)
            throw new IllegalArgumentException("请选择图片");
        int limit = (purpose == ImagePurpose.AVATAR ? 2 : 10) * 1024 * 1024;
        if (bytes.length > limit)
            throw new IllegalArgumentException("图片超过大小限制（头像 2 MiB，其他 10 MiB）");
        String detected = ImageSignaturePolicy.detect(bytes);
        if (detected == null || !detected.equals(mediaType))
            throw new IllegalArgumentException("仅支持真实的 JPEG、PNG、GIF、WebP 图片，文件类型必须匹配");
        this.bytes = bytes.clone();
        this.mediaType = mediaType;
        this.purpose = purpose;
        this.extension =
                switch (mediaType) {
                    case "image/jpeg" -> "jpg";
                    case "image/png" -> "png";
                    case "image/gif" -> "gif";
                    default -> "webp";
                };
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
