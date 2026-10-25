package com.blogsystem.application.asset.upload.port;

import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTarget;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import java.util.UUID;

/** Business-specific bridge; implementations must not depend on the upload coordinator. */
public interface UploadBindingApplicationPort {
    MediaUploadTargetType targetType();

    MediaUploadTarget prepareTarget(MediaUploadRequest request, UUID candidateAssetId);

    UploadBindingPreparation prepareCommit(MediaUploadOperation.Intent intent);
}
