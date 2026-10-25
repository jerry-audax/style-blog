package com.blogsystem.domain.identity.model.valobj;

import java.util.Objects;

public final class OwnerTokenVerificationException extends RuntimeException {
    public enum Reason {
        REVOKED,
        UNAVAILABLE
    }

    private final Reason reason;

    public OwnerTokenVerificationException(Reason reason) {
        super("Owner token verification failed");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
