package com.blogsystem.infrastructure.mybatis.record.asset;

/** Exact business identity and optional URL; slot is a server-side SQL literal. */
public record MediaReferenceBindingRecord(String businessId, String resourceUrl, String slot) {}
