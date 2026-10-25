package com.blogsystem.application.surprise.result;

public record SurpriseVideoAdminView(
        String id,
        String title,
        String url,
        String path,
        String mediaType,
        Long size,
        boolean enabled,
        int sortOrder,
        Long version) {}
