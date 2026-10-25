package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.image.gateway.ImageStorageGateway;
import com.blogsystem.domain.asset.image.model.query.ImagePage;
import com.blogsystem.domain.asset.image.model.valobj.ImagePath;
import com.blogsystem.domain.asset.image.model.valobj.ImageStorageException;
import com.blogsystem.domain.asset.image.model.valobj.ImageUpload;
import com.blogsystem.domain.asset.image.model.valobj.StoredImage;

import java.util.Objects;

/** The sole legacy image gateway; postgres operations must use durable metadata protocols. */
public final class DeletionGuardedImageStorageGateway implements ImageStorageGateway {
    private final ImageStorageGateway delegate;
    private final boolean legacyAllowed;

    public DeletionGuardedImageStorageGateway(
            ImageStorageGateway delegate, boolean legacyAllowed) {
        this.delegate = Objects.requireNonNull(delegate);
        this.legacyAllowed = legacyAllowed;
    }

    @Override
    public StoredImage upload(ImageUpload image, long owner) {
        requireLegacy();
        return delegate.upload(image, owner);
    }

    @Override
    public ImagePage list(int offset, int count, String search) {
        requireLegacy();
        return delegate.list(offset, count, search);
    }

    @Override
    public void delete(ImagePath path) {
        requireLegacy();
        delegate.delete(path);
    }

    private void requireLegacy() {
        if (!legacyAllowed) throw new ImageStorageException(ImageStorageException.Reason.OPERATION_NOT_READY);
    }
}
