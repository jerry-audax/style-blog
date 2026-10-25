package com.blogsystem.domain.asset.inventory.model.valobj;

import java.util.Objects;

/** Safe offline outcome. Raw provider/SQL exception messages and credentials are never retained. */
public final class MediaImportFailure extends RuntimeException {
    public enum Outcome {
        ROLLBACK_ATTEMPTED,
        COMMIT_UNKNOWN,
        COMMITTED
    }

    private final String stage;
    private final Outcome outcome;
    private final String errorType;

    public MediaImportFailure(String stage, Outcome outcome, String errorType) {
        super("Media metadata import failed");
        if (stage == null || !stage.matches("[A-Z_]{1,40}"))
            throw new IllegalArgumentException("Invalid import stage");
        if (errorType == null || !errorType.matches("[A-Za-z0-9_$]{1,100}"))
            errorType = "RuntimeException";
        this.stage = stage;
        this.outcome = Objects.requireNonNull(outcome);
        this.errorType = errorType;
    }

    public String stage() {
        return stage;
    }

    public Outcome outcome() {
        return outcome;
    }

    public String errorType() {
        return errorType;
    }
}
