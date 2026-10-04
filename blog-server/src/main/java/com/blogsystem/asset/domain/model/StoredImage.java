package com.blogsystem.asset.domain.model;

public record StoredImage(String path, String url, String mediaType, long size) {
}
