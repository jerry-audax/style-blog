package com.blogsystem.domain.asset.upload.model.valobj;

import java.io.IOException;
import java.io.InputStream;

/** Reopenable source for this request only; must never be persisted or retained by a worker. */
public interface MediaUploadContent {
    long size();

    String fileName();

    String mediaType();

    InputStream openStream() throws IOException;
}
