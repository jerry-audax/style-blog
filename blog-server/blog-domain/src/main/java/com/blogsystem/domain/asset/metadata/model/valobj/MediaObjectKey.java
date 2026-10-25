package com.blogsystem.domain.asset.metadata.model.valobj;

/**
 * A provider object key, rather than a URL or a local filesystem path.
 */
public record MediaObjectKey(String value) {

    public MediaObjectKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("资源路径不能为空");
        }
        if (value.length() > 512) {
            throw new IllegalArgumentException("资源路径过长");
        }
        if (value.startsWith("/") || value.endsWith("/")) {
            throw new IllegalArgumentException("资源路径不能以斜杠开头或结尾");
        }
        if (value.contains("\\")
                || value.contains("?")
                || value.contains("#")
                || value.contains(":")) {
            throw new IllegalArgumentException("资源路径格式不合法");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("资源路径不能包含控制字符");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("资源路径包含非法目录段");
            }
        }
    }

    public void requireWithin(String root) {
        if (root == null || root.isBlank() || root.endsWith("/")) {
            throw new IllegalArgumentException("资源根目录不能为空或以斜杠结尾");
        }
        if (!value.startsWith(root + "/")) {
            throw new IllegalArgumentException("资源不在指定目录内");
        }
    }
}
