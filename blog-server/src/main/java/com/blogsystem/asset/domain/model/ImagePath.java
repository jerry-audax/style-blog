package com.blogsystem.asset.domain.model;

/**
 * Decoded relative object key, never a URL or a directory deletion target.
 */
public record ImagePath(String value) {
    public ImagePath {
        if (value == null || value.isBlank() || value.length() > 1024 || value.startsWith("/") || value.endsWith("/") ||
                value.matches(".*[\\\\%?#:<>\"].*") || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("无效图片路径");
        for (String segment : value.split("/", -1))
            if (segment.isBlank() || segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("无效图片路径");
        if (!value.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp)$"))
            throw new IllegalArgumentException("只能操作具体图片，不能操作文件夹");
    }

    public void requireWithin(String root) {
        if (!value.startsWith(root + "/")) throw new IllegalArgumentException("只能管理本博客目录内的图片");
    }
}
