package com.blogsystem.domain.asset.upload.model.query;

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;

import java.util.List;

public record MediaUploadPage(
        List<MediaUploadOperation> records, long total, int offset, int limit) {
    public MediaUploadPage {
        records = List.copyOf(records);
        if (total < 0
                || offset < 0
                || limit < 1
                || limit > 100
                || records.size() > limit
                || records.size() > total)
            throw new IllegalArgumentException("Invalid upload page");
    }
}
