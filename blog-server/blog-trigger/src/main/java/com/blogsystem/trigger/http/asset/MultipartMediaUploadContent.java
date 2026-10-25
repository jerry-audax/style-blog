package com.blogsystem.trigger.http.asset;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/** Request-scoped adapter; no getBytes and no asynchronous retention of the multipart file. */
public final class MultipartMediaUploadContent implements MediaUploadContent {
    private final MultipartFile file;

    public MultipartMediaUploadContent(MultipartFile file) {
        this.file = java.util.Objects.requireNonNull(file);
    }

    public long size() {
        return file.getSize();
    }

    public String fileName() {
        return file.getOriginalFilename();
    }

    public String mediaType() {
        String value = file.getContentType();
        return value == null ? null : value.split(";", 2)[0].trim();
    }

    public InputStream openStream() throws IOException {
        return file.getInputStream();
    }
}
