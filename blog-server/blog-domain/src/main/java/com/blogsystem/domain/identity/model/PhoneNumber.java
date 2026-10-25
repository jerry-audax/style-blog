package com.blogsystem.domain.identity.model;

import com.blogsystem.types.exception.DomainException;

public record PhoneNumber(String value) {
    public PhoneNumber {
        if (value == null || !value.matches("1\\d{10}")) {
            throw new DomainException("手机号格式不正确");
        }
    }
}
