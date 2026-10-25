package com.blogsystem.infrastructure.mybatis.record.asset;

/** Only columns needed for resource inspection, never the full article persistence shape. */
public record MediaArticleReferenceRecord(Long id, String coverUrl, String contentMd, String contentHtml) {}
