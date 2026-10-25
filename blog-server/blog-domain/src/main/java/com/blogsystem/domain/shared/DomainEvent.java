package com.blogsystem.domain.shared;

import java.time.Instant;

/**
 * A domain fact that can be dispatched outside the aggregate boundary.
 */
public interface DomainEvent {

    String eventType();

    Instant occurredAt();
}
