package com.blogsystem.domain.asset.reference.model.valobj;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import java.util.Set;

/** A partial scan keeps positive matches; failures must never become empty successful evidence. */
public record MediaLinkScan(Set<MediaObjectKey> objectKeys, Set<Failure> failures) {
    public enum Failure { AMBIGUOUS, UNSUPPORTED, LIMIT_EXCEEDED }

    public MediaLinkScan {
        if (objectKeys == null || failures == null || objectKeys.stream().anyMatch(key -> key == null)
                || failures.stream().anyMatch(failure -> failure == null)) {
            throw new IllegalArgumentException("资源链接扫描结果不完整");
        }
        objectKeys = Set.copyOf(objectKeys);
        failures = Set.copyOf(failures);
    }

    public boolean complete() { return failures.isEmpty(); }
}
