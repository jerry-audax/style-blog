package com.blogsystem.application.asset.upload.service;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadExecutionFence;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;

import org.springframework.transaction.support.TransactionOperations;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Synchronous request execution with independently committed, bounded heartbeats. */
public final class MediaUploadLeaseRunner {
    private final MediaUploadOperationRepository operations;
    private final TransactionOperations transactions;
    private final ScheduledExecutorService scheduler;

    public MediaUploadLeaseRunner(
            MediaUploadOperationRepository operations,
            TransactionOperations transactions,
            ScheduledExecutorService scheduler) {
        this.operations = operations;
        this.transactions = transactions;
        this.scheduler = scheduler;
    }

    public <T> T withLease(
            MediaUploadExecutionFence fence, Function<MediaUploadLeaseGuard, T> work) {
        var pending = new AtomicReference<ScheduledFuture<?>>();
        try (var guard =
                new MediaUploadLeaseGuard(
                        () ->
                                Boolean.TRUE.equals(
                                        transactions.execute(s -> operations.renewLease(fence))),
                        () -> {
                            var future = pending.get();
                            if (future != null) future.cancel(false);
                        })) {
            guard.assertCurrent();
            pending.set(scheduler.scheduleAtFixedRate(guard::pulse, 30, 30, TimeUnit.SECONDS));
            return work.apply(guard);
        }
    }
}
