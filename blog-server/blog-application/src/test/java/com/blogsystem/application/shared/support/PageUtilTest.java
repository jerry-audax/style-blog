package com.blogsystem.application.shared.support;

import com.blogsystem.application.shared.support.PageUtil;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-3 分页参数上限单元测试。
 * 验证 {@link PageUtil} 对 pageNum（最小1）和 pageSize（1~100）的边界处理。
 */
class PageUtilTest {

    @Test
    void clampPageNum_belowMinimum_returnsOne() {
        assertEquals(1L, PageUtil.clampPageNum(0L));
        assertEquals(1L, PageUtil.clampPageNum(-1L));
        assertEquals(1L, PageUtil.clampPageNum(-999L));
    }

    @Test
    void clampPageNum_normal_returnsSame() {
        assertEquals(1L, PageUtil.clampPageNum(1L));
        assertEquals(5L, PageUtil.clampPageNum(5L));
        assertEquals(100L, PageUtil.clampPageNum(100L));
    }

    @Test
    void clampPageSize_belowMinimum_returnsOne() {
        assertEquals(1L, PageUtil.clampPageSize(0L));
        assertEquals(1L, PageUtil.clampPageSize(-1L));
    }

    @Test
    void clampPageSize_normal_returnsSame() {
        assertEquals(10L, PageUtil.clampPageSize(10L));
        assertEquals(50L, PageUtil.clampPageSize(50L));
    }

    @Test
    void clampPageSize_exceedsMaximum_clampedTo100() {
        assertEquals(100L, PageUtil.clampPageSize(101L));
        assertEquals(100L, PageUtil.clampPageSize(999L));
        assertEquals(100L, PageUtil.clampPageSize(10000L));
    }

    @Test
    void clampPageSize_atBoundary_returnsExactly() {
        assertEquals(1L, PageUtil.clampPageSize(1L));
        assertEquals(100L, PageUtil.clampPageSize(100L));
    }
}
