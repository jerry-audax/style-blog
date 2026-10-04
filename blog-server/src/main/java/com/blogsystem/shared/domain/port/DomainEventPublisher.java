package com.blogsystem.shared.domain.port;

import com.blogsystem.shared.domain.DomainEvent;

@FunctionalInterface
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
