package com.blogsystem.domain.asset.upload.gateway;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;

public interface MediaUploadContentVerifier {
    void verify(MediaUploadFile expected, MediaUploadSlot slot, MediaUploadContent source);
}
