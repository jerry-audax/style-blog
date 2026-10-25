package com.blogsystem.app.it.application.asset.upload.service;

import com.blogsystem.application.asset.upload.service.MediaUploadLeaseRunner;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadExecutionFence;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;

import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException.Reason;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionOperations;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaUploadLeaseRunnerTest {
    @Test void databaseOutageIsUnavailableNotAConcurrentEdit() {
        var repo=mock(MediaUploadOperationRepository.class);var executor=mock(ScheduledExecutorService.class);
        var fence=new MediaUploadExecutionFence(UUID.randomUUID(),1,UUID.randomUUID(),1);
        when(repo.renewLease(fence)).thenThrow(new MediaUploadException(MediaUploadException.Reason.UNAVAILABLE));
        var runner=new MediaUploadLeaseRunner(repo,new TransactionOperations(){public <T>T execute(TransactionCallback<T> callback){return callback.doInTransaction(new SimpleTransactionStatus());}},executor);
        assertEquals(MediaUploadException.Reason.UNAVAILABLE,assertThrows(MediaUploadException.class,()->runner.withLease(fence,guard->null)).reason());
    }
    @Test void heartbeatFailureStopsSubsequentDispatchAndCloseCancelsOnlyHeartbeat(){
        var repo=mock(MediaUploadOperationRepository.class);var executor=mock(ScheduledExecutorService.class);var future=mock(ScheduledFuture.class);var task=new AtomicReference<Runnable>();
        when(executor.scheduleAtFixedRate(any(Runnable.class),eq(30L),eq(30L),eq(TimeUnit.SECONDS))).thenAnswer(a->{task.set(a.getArgument(0));return future;});
        var fence=new MediaUploadExecutionFence(UUID.randomUUID(),1,UUID.randomUUID(),1);when(repo.renewLease(fence)).thenReturn(true,false);
        var runner=new MediaUploadLeaseRunner(repo,new TransactionOperations(){public <T>T execute(TransactionCallback<T> callback){return callback.doInTransaction(new SimpleTransactionStatus());}},executor);
        runner.withLease(fence,guard->{task.get().run();assertEquals(MediaUploadException.Reason.CONFLICT,assertThrows(MediaUploadException.class,guard::assertCurrent).reason());return null;});
        verify(future).cancel(false);verify(executor,never()).shutdown();
    }
}
