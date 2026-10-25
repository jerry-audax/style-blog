package com.blogsystem.application.asset.upload.service;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Losing a lease is irreversible for this worker; close only stops its heartbeat. */
public final class MediaUploadLeaseGuard implements AutoCloseable {
    private final BooleanSupplier renew;
    private final Runnable cancel;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<MediaUploadException> failure = new AtomicReference<>();

    MediaUploadLeaseGuard(BooleanSupplier renew, Runnable cancel) {
        this.renew = renew;
        this.cancel = cancel;
    }

    void pulse() {
        if (closed.get() || failure.get() != null) return;
        try {
            if (!renew.getAsBoolean()) failure.compareAndSet(null, lost());
        } catch (RuntimeException ignored) {
            failure.compareAndSet(
                    null,
                    new MediaUploadException(
                            MediaUploadException.Reason.UNAVAILABLE,
                            MediaUploadErrorCode.DATABASE_UNAVAILABLE));
        }
    }

    public void assertCurrent() {
        if (closed.get()) throw lost();
        pulse();
        var stopped = failure.get();
        if (stopped != null) throw stopped;
    }

    private static MediaUploadException lost() {
        return new MediaUploadException(
                MediaUploadException.Reason.CONFLICT, MediaUploadErrorCode.LEASE_LOST);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) cancel.run();
    }
}
