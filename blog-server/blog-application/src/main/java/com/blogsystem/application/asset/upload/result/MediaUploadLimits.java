package com.blogsystem.application.asset.upload.result;

public record MediaUploadLimits(
        long audioBytes, long coverBytes, long lyricsBytes, long videoBytes, long chunkBytes) {}
