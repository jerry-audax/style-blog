package com.blogsystem.infrastructure.event;

import com.blogsystem.infrastructure.event.SpringDomainEventPublisher;

import org.mockito.Mockito;

import com.blogsystem.domain.content.event.ArticlePublished;
import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.UserId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static org.mockito.Mockito.*;

class SpringDomainEventPublisherTest {
    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void notificationsWaitForCommit() {
        var spring = mock(ApplicationEventPublisher.class);
        var event = new ArticlePublished(new ArticleId(1L), new UserId(42L), Instant.EPOCH);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        new SpringDomainEventPublisher(spring).publish(event);
        verifyNoInteractions(spring);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(spring).publishEvent(event);
    }

    @Test
    void rollbackDoesNotNotifyConsumers() {
        var spring = mock(ApplicationEventPublisher.class);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        new SpringDomainEventPublisher(spring).publish(new ArticlePublished(new ArticleId(1L), new UserId(42L), Instant.EPOCH));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(spring);
    }
}
