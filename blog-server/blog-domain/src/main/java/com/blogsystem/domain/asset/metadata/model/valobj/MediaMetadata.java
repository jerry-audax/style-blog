package com.blogsystem.domain.asset.metadata.model.valobj;

/**
 * Facts known about a provider object. A null measurement means the value is
 * unknown; zero remains a valid measured size or duration.
 */
public record MediaMetadata(
        String fileName,
        String mimeType,
        Long fileSize,
        String sha256,
        Integer width,
        Integer height,
        Long durationMs) {

    public MediaMetadata {
        requireText(fileName, "文件名", 255, false);
        if (mimeType != null) requireText(mimeType, "媒体类型", 128, false);
        if (fileSize != null && fileSize < 0) {
            throw new IllegalArgumentException("文件大小不能小于零");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("媒体时长不能小于零");
        }
        if (width != null && width <= 0) {
            throw new IllegalArgumentException("媒体宽度必须大于零");
        }
        if (height != null && height <= 0) {
            throw new IllegalArgumentException("媒体高度必须大于零");
        }
        if (sha256 != null && !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 格式不合法");
        }
    }

    private static void requireText(String value, String field, int maxLength, boolean nullable) {
        if (value == null) {
            if (nullable) return;
            throw new IllegalArgumentException(field + "不能为空");
        }
        if (value.isBlank()
                || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + "格式不合法");
        }
    }
}
