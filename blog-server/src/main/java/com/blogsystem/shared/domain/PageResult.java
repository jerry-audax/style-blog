package com.blogsystem.shared.domain;

import java.util.List;
import java.util.Objects;

public record PageResult<T>(List<T> records, long pageNumber, long pageSize, long total) {

    public PageResult {
        records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
    }
}
