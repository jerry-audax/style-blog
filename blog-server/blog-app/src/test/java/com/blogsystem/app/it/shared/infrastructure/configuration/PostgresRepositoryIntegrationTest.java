package com.blogsystem.app.it.shared.infrastructure.configuration;

import com.blogsystem.app.config.MybatisPlusConfig;

import java.util.Objects;
import org.junit.jupiter.api.Assertions;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import com.blogsystem.infrastructure.mybatis.record.content.TagRecord;
import com.blogsystem.infrastructure.mybatis.mapper.content.TagMapper;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerRoleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerAccountMapper;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerAccountRoleMapper;
import com.blogsystem.infrastructure.adapter.repository.identity.MybatisOwnerAccountRepository;
import com.blogsystem.infrastructure.mybatis.mapper.administration.LoginLogMapper;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// Explicit opt-in only. Uses rollback; never logs into real users or contacts AI.
@EnabledIfEnvironmentVariable(named = "PG_TEST_URL", matches = ".+")
class PostgresRepositoryIntegrationTest {
    @Test
    void realPostgresSupportsPaginationGeneratedIdsUpdatesAndOwnerRoleModel() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("PG_TEST_URL"),
                System.getenv().getOrDefault("PG_TEST_USERNAME", "postgres"), System.getenv("PG_TEST_PASSWORD"));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ArticleMapper.class);
        config.addMapper(TagMapper.class);
        config.addMapper(OwnerRoleMapper.class);
        config.addMapper(OwnerAccountMapper.class);
        config.addMapper(OwnerAccountRoleMapper.class);
        config.addMapper(LoginLogMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(config);
        factory.setTransactionFactory(new JdbcTransactionFactory());
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor());

        try (var session = factory.getObject().openSession(false)) {
            try {
                var articles = session.getMapper(ArticleMapper.class);
                var page = articles.selectPage(new Page<ArticleRecord>(1, 2),
                        new LambdaQueryWrapper<ArticleRecord>().eq(ArticleRecord::getDeleted, 0).orderByAsc(ArticleRecord::getId));
                assertTrue(page.getTotal() > 0);
                assertEquals(2, page.getRecords().size());
                assertNotNull(page.getRecords().get(0).getContentMd());

                var tags = session.getMapper(TagMapper.class);
                var tag = new TagRecord();
                tag.setName("migration-qa");
                tag.setSlug("migration-qa-" + UUID.randomUUID());
                assertEquals(1, tags.insert(tag));
                assertNotNull(tag.getId(), "PostgreSQL JDBC must return identity IDs");
                var created = tags.selectById(tag.getId());
                assertNotNull(created.getCreatedAt());
                tag.setColor("#123456");
                assertEquals(1, tags.updateById(tag));
                var updated = tags.selectById(tag.getId());
                assertEquals("#123456", updated.getColor());
                assertTrue(updated.getUpdatedAt().isAfter(created.getUpdatedAt()), "Automatic update timestamp must survive migration");

                var ownerRole = session.getMapper(OwnerRoleMapper.class).selectOne(
                        new LambdaQueryWrapper<com.blogsystem.infrastructure.mybatis.record.identity.OwnerRoleRecord>()
                                .eq(com.blogsystem.infrastructure.mybatis.record.identity.OwnerRoleRecord::getRoleCode, "BLOG_OWNER"));
                assertNotNull(ownerRole, "The reduced role model must contain BLOG_OWNER");
                assertEquals(1, ownerRole.getStatus());
                // Legacy profile fixture only; guarded PostgreSQL writes have separate owned-schema tests.
                var owners = new MybatisOwnerAccountRepository(session.getMapper(OwnerAccountMapper.class),
                        session.getMapper(LoginLogMapper.class), (previous, desired) -> {});
                var owner = owners.findByPhone(System.getenv().getOrDefault("BLOG_OWNER_PHONE", "19140838906")).orElseThrow();
                assertTrue(owner.active());
                assertTrue(owner.passwordHash() != null && !owner.passwordHash().isBlank());
                var edited = owners.updateProfile(owner.id(), "owner-rollback-fixture", null, null);
                assertEquals("owner-rollback-fixture", edited.nickname());
                owners.recordSuccessfulLogin(owner.id(), null);
                owners.changePassword(owner.id(), "fixture-hash-never-committed");
                assertTrue("fixture-hash-never-committed".equals(owners.findById(owner.id()).orElseThrow().passwordHash()));
                session.rollback(true);
                assertTrue(java.util.Objects.equals(owner.passwordHash(), owners.findById(owner.id()).orElseThrow().passwordHash()), "Password fixture must roll back");
                assertNull(tags.selectById(tag.getId()), "Fixture row must not be committed");
            } finally {
                session.rollback(true);
            }
        }
    }
}
