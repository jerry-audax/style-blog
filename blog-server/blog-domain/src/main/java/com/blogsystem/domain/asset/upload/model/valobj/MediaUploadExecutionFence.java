package com.blogsystem.domain.asset.upload.model.valobj;

import java.util.Objects;
import java.util.UUID;

public record MediaUploadExecutionFence(
        UUID operationId, long actorId, UUID workerId, long fencingToken) {
    public MediaUploadExecutionFence {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(workerId);
        if (actorId <= 0 || fencingToken <= 0)
            throw new IllegalArgumentException("Invalid upload execution fence");
    }
}
