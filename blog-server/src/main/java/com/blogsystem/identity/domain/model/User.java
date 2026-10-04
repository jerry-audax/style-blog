package com.blogsystem.identity.domain.model;

import com.blogsystem.shared.domain.DomainException;

import java.time.Instant;
import java.util.Objects;

public final class User {
    private final UserId id;
    private final PhoneNumber phone;
    private final Instant registeredAt;
    private UserStatus status;
    private Instant updatedAt;

    private User(UserId id, PhoneNumber phone, Instant registeredAt) {
        this.id = Objects.requireNonNull(id);
        this.phone = Objects.requireNonNull(phone);
        this.registeredAt = Objects.requireNonNull(registeredAt);
        this.updatedAt = registeredAt;
        this.status = UserStatus.ACTIVE;
    }

    public static User register(UserId id, PhoneNumber phone, Instant registeredAt) {
        return new User(id, phone, registeredAt);
    }

    public void disable(Instant occurredAt) {
        if (status == UserStatus.DISABLED) throw new DomainException("用户已禁用");
        status = UserStatus.DISABLED;
        updatedAt = Objects.requireNonNull(occurredAt);
    }

    public void enable(Instant occurredAt) {
        status = UserStatus.ACTIVE;
        updatedAt = Objects.requireNonNull(occurredAt);
    }

    public UserId id() {
        return id;
    }

    public PhoneNumber phone() {
        return phone;
    }

    public UserStatus status() {
        return status;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
