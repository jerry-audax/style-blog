package com.blogsystem.types.model;

import java.util.Objects;

public interface Identifier<T> {

    T value();

    static <T> T requireValue(T value) {
        return Objects.requireNonNull(value, "identifier value must not be null");
    }
}
