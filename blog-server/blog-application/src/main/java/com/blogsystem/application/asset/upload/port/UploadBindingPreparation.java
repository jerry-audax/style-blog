package com.blogsystem.application.asset.upload.port;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResourceSnapshot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadResultFacts;

import java.time.Instant;

/** Commits through business domain repositories inside the caller's transaction, with no network. */
@FunctionalInterface
public interface UploadBindingPreparation {
    MediaUploadResultFacts commit(MediaUploadResourceSnapshot newResource, Instant now);
}
