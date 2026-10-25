package com.blogsystem.domain.asset.upload.gateway;

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadProbe;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadReceipt;

public interface MediaUploadStorageGateway {
    MediaUploadReceipt uploadDirect(MediaUploadOperation.Intent intent, MediaUploadContent source);

    String beginChunks(MediaUploadOperation.Intent intent);

    void uploadChunk(
            MediaUploadOperation.Intent intent, String uploadId, int chunkIndex, byte[] bytes);

    MediaUploadReceipt mergeChunks(MediaUploadOperation.Intent intent, String uploadId);

    MediaUploadProbe probe(MediaUploadOperation.Intent intent);
}
