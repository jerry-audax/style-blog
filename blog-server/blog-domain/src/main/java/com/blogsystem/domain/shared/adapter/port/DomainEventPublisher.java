package com.blogsystem.domain.shared.adapter.port;

import com.blogsystem.domain.shared.DomainEvent;

@FunctionalInterface
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
