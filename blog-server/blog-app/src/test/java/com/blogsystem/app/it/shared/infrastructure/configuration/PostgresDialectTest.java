package com.blogsystem.app.it.shared.infrastructure.configuration;

import com.blogsystem.app.config.MybatisPlusConfig;

import org.junit.jupiter.api.Assertions;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgresDialectTest {
    @Test
    void paginationUsesPostgresDialect() {
        var plugins = new MybatisPlusConfig().mybatisPlusInterceptor().getInterceptors();
        var pagination = (PaginationInnerInterceptor) plugins.get(0);
        assertEquals(DbType.POSTGRE_SQL, pagination.getDbType());
    }
}
