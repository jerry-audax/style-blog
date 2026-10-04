package com.blogsystem.ai.domain.knowledge;

import java.time.Instant;
import java.util.UUID;

public final class IndexBuild {
    private final String id = UUID.randomUUID().toString();
    private final String reason;
    private final Instant queuedAt = Instant.now();
    private IndexBuildStatus status = IndexBuildStatus.QUEUED;
    private int chunkCount;
    private String error;

    private IndexBuild(String reason) {
        this.reason = reason;
    }

    public static IndexBuild queue(String reason) {
        return new IndexBuild(reason == null ? "unknown" : reason);
    }

    public void start() {
        if (status == IndexBuildStatus.QUEUED) status = IndexBuildStatus.RUNNING;
    }

    public void complete(int chunks) {
        if (chunks < 0) throw new IllegalArgumentException("chunks must not be negative");
        status = IndexBuildStatus.COMPLETED;
        chunkCount = chunks;
    }

    public void fail(String message) {
        status = IndexBuildStatus.FAILED;
        error = message;
    }

    public String id() {
        return id;
    }

    public String reason() {
        return reason;
    }

    public Instant queuedAt() {
        return queuedAt;
    }

    public IndexBuildStatus status() {
        return status;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public String error() {
        return error;
    }
}
