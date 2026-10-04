package com.blogsystem.shared.interfaces.http;

import com.blogsystem.shared.domain.PageResult;

import java.util.List;

/**
 * HTTP pagination contract; never expose a vendor Page object.
 */
public record PageResponse<T>(List<T> records, long total, long size, long current, long pages) {
    public static <T> PageResponse<T> from(PageResult<T> result) {
        long pages = result.total() / result.pageSize() + (result.total() % result.pageSize() == 0 ? 0 : 1);
        return new PageResponse<>(result.records(), result.total(), result.pageSize(), result.pageNumber(), pages);
    }
}

