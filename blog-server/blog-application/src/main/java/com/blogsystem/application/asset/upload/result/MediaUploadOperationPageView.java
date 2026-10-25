package com.blogsystem.application.asset.upload.result;

import java.util.List;

public record MediaUploadOperationPageView(
        List<MediaUploadOperationView> records, long total, int offset, int limit) {
    public MediaUploadOperationPageView {
        records = List.copyOf(records);
    }
}
