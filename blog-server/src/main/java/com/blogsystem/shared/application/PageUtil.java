package com.blogsystem.shared.application;

/**
 * 分页参数规范化工具 — 防止超大数据集请求拖垮数据库
 */
public final class PageUtil {

    private static final long MAX_PAGE_SIZE = 100;
    private static final long MIN_PAGE_NUM = 1;
    private static final long MIN_PAGE_SIZE = 1;

    public static long clampPageNum(long pageNum) {
        return Math.max(MIN_PAGE_NUM, pageNum);
    }

    public static long clampPageSize(long pageSize) {
        return Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, pageSize));
    }

    private PageUtil() {
    }
}
