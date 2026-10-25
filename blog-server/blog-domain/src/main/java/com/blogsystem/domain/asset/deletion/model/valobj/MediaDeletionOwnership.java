package com.blogsystem.domain.asset.deletion.model.valobj;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** A local, auditable assertion; it is not proof of remote reference absence. */
public record MediaDeletionOwnership(
        Scope scope,
        String exclusiveEnvironment,
        Long confirmedBy,
        Instant confirmedAt,
        String note) {
    public enum Scope {
        UNVERIFIED,
        SHARED,
        EXCLUSIVE
    }

    public MediaDeletionOwnership {
        Objects.requireNonNull(scope);
        if (scope == Scope.UNVERIFIED
                && (exclusiveEnvironment != null
                        || confirmedBy != null
                        || confirmedAt != null
                        || note != null)) throw new IllegalArgumentException("未知归属不能附带确认声明");
        if (scope != Scope.EXCLUSIVE && exclusiveEnvironment != null)
            throw new IllegalArgumentException("非独占资源不能指定独占环境");
        if ((confirmedBy == null) != (confirmedAt == null)
                || confirmedBy != null && confirmedBy <= 0)
            throw new IllegalArgumentException("无效归属确认人或时间");
        if (confirmedAt != null) confirmedAt = confirmedAt.truncatedTo(ChronoUnit.MICROS);
        if (note != null
                && (note.isBlank()
                        || note.length() > 500
                        || note.chars().anyMatch(Character::isISOControl)
                        || note.contains("://")
                        || note.contains("imgbed_")
                        || note.contains("-----BEGIN")))
            throw new IllegalArgumentException("确认依据不能包含URL、凭据或控制字符");
        if (scope == Scope.EXCLUSIVE
                && (exclusiveEnvironment == null
                        || !exclusiveEnvironment.matches("[a-z][a-z0-9-]{0,31}")
                        || confirmedBy == null
                        || note == null)) throw new IllegalArgumentException("独占声明必须记录环境、博主、时间和依据");
    }

    public static MediaDeletionOwnership unverified() {
        return new MediaDeletionOwnership(Scope.UNVERIFIED, null, null, null, null);
    }
}
