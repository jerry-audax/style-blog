package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;

import java.util.Locale;

/** Description of source bytes, not a local path or proof of provider contents. */
public record MediaUploadFile(String fileName, String mediaType, long size, String sourceSha256) {
    public MediaUploadFile {
        fileName = MediaUploadPolicy.text(fileName, 255, true);
        if (fileName.contains("/") || fileName.contains("\\"))
            throw new IllegalArgumentException("Invalid source filename");
        mediaType = MediaUploadPolicy.text(mediaType, 128, true).toLowerCase(Locale.ROOT);
        if (!mediaType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))
            throw new IllegalArgumentException("Invalid source MIME");
        if (size <= 0) throw new IllegalArgumentException("Source file must be nonempty");
        if (sourceSha256 == null) throw new IllegalArgumentException("Source hash is required");
        sourceSha256 = sourceSha256.toLowerCase(Locale.ROOT);
        if (!sourceSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid source hash");
    }

    @Override
    public String toString() {
        return "MediaUploadFile[size=" + size + "]";
    }
}
