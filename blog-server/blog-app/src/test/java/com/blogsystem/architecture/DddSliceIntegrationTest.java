package com.blogsystem.architecture;

import com.blogsystem.app.config.DomainRuntimeConfiguration;
import com.blogsystem.app.config.MybatisPlusConfig;

import com.blogsystem.domain.interaction.adapter.repository.CommentRepository;
import com.blogsystem.infrastructure.adapter.repository.content.MybatisArticleRepository;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleLikeMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleTagMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.CategoryMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.TagMapper;
import com.blogsystem.infrastructure.mybatis.mapper.interaction.CommentMapper;

import com.blogsystem.application.content.service.ArticleCommandService;
import com.blogsystem.application.content.service.ContentApplicationFacade;

import com.blogsystem.domain.content.model.ArticleId;
import com.blogsystem.domain.content.model.ArticleRevision;
import com.blogsystem.domain.content.model.ArticleStatus;
import com.blogsystem.domain.content.model.ContentBody;
import com.blogsystem.domain.content.model.ContentFormat;

import org.springframework.context.annotation.Configuration;

import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.blogsystem.application.content.command.SaveArticleCommand;
import com.blogsystem.domain.content.adapter.repository.ArticleRepository;
import com.blogsystem.application.interaction.service.InteractionApplicationFacade;
import com.blogsystem.domain.interaction.model.CommentSubmission;
import com.blogsystem.domain.interaction.gateway.CommentRateLimit;
import com.blogsystem.infrastructure.redis.interaction.RedisCommentRateLimit;
import com.blogsystem.infrastructure.mybatis.mapper.identity.OwnerAccountMapper;
import com.blogsystem.application.administration.service.AdministrationApplicationFacade;
import com.blogsystem.types.exception.DomainException;
import com.blogsystem.domain.shared.adapter.port.CurrentActor;
import com.blogsystem.infrastructure.mybatis.mapper.administration.OperationLogMapper;
import com.blogsystem.infrastructure.event.SpringDomainEventPublisher;
import com.blogsystem.infrastructure.redis.support.RedisFailurePolicy;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real Spring wiring + MyBatis + transactions in an isolated H2 PostgreSQL-mode fixture.
 * No real PostgreSQL, Redis, cloud storage, login credentials or AI requests.
 */
class DddSliceIntegrationTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    ContentApplicationFacade content;

    @BeforeEach
    void start() {
        context = new AnnotationConfigApplicationContext(Fixture.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        content = context.getBean(ContentApplicationFacade.class);
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
        context.close();
    }

    SaveArticleCommand command(Long id, ArticleStatus status, List<Long> tags) {
        return new SaveArticleCommand(id, new ArticleRevision("DDD", "summary",
                new ContentBody("<p>body</p>", ContentFormat.HTML), "https://example.test/cover", null,
                status, 0, 1, tags));
    }

    long scalar(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void componentScanAssemblesRepositoriesAndTransactionalUseCases() {
        assertThat(AopUtils.isAopProxy(content)).isTrue();
        assertThat(AopUtils.isAopProxy(context.getBean(ArticleCommandService.class))).isTrue();
        assertThat(AopUtils.getTargetClass(context.getBean(ArticleRepository.class)))
                .isEqualTo(MybatisArticleRepository.class);
        assertThat(context.getBean(InteractionApplicationFacade.class)).isNotNull();
        assertThat(context.getBean(AdministrationApplicationFacade.class).dashboard().pendingComments()).isZero();
    }

    @Test
    void draftPublicationAndReadsCrossAllBusinessLayers() {
        Long id = content.saveArticle(command(null, ArticleStatus.DRAFT, List.of(1L, 1L, 2L)));
        assertThat(id).isPositive();
        assertThat(scalar("select count(*) from article_tag")).isEqualTo(2);
        assertThat(scalar("select author_id from article")).isEqualTo(42);
        assertThat(jdbc.queryForObject("select content_html from article", String.class)).isEqualTo("<p>body</p>");
        assertThatThrownBy(() -> content.getArticle(id, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(scalar("select view_count from article")).isZero();
        content.saveArticle(command(id, ArticleStatus.PUBLISHED, List.of(2L)));
        assertThat(content.getArticle(id, false).viewCount()).isEqualTo(1);
        assertThat(scalar("select count(*) from article_tag")).isEqualTo(1);
        assertThat(content.listArticles(1, null, null, 1, 10).total()).isEqualTo(1);
    }

    @Test
    void editsDoNotOverwriteCountersOrAuthorAndCanClearNullableMetadata() {
        Long id = content.saveArticle(command(null, ArticleStatus.PUBLISHED, List.of()));
        jdbc.update("update article set view_count=17,like_count=9,collect_count=2 where id=?", id);
        var edit = new ArticleRevision("edit", null, new ContentBody("# Markdown", ContentFormat.MARKDOWN),
                null, null, ArticleStatus.DRAFT, 0, 1, List.of());
        var published = jdbc.queryForObject("select publish_time from article", java.time.LocalDateTime.class);
        content.saveArticle(new SaveArticleCommand(id, edit));
        assertThat(scalar("select view_count from article")).isEqualTo(17);
        assertThat(scalar("select like_count from article")).isEqualTo(9);
        assertThat(scalar("select collect_count from article")).isEqualTo(2);
        assertThat(scalar("select author_id from article")).isEqualTo(42);
        assertThat(jdbc.queryForObject("select summary from article", String.class)).isNull();
        assertThat(jdbc.queryForObject("select content_html from article", String.class)).contains("<h1>Markdown</h1>");
        assertThat(jdbc.queryForObject("select publish_time from article", java.time.LocalDateTime.class)).isEqualTo(published);
    }

    @Test
    void detailReadReturnsTagIdsSoEditingDoesNotDropBindings() {
        Long id = content.saveArticle(command(null, ArticleStatus.PUBLISHED, List.of(11L, 12L)));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .valueToTree(content.getArticle(id, true));
        assertThat(json.has("tagIds")).isTrue();
        assertThat(json.get("tagIds").get(0).longValue()).isEqualTo(11L);
        assertThat(json.get("tagIds").get(1).longValue()).isEqualTo(12L);
    }

    @Test
    void bodyAndTagsRollbackTogetherAndCacheIsNotEvictedOnRollback() {
        Long id = content.saveArticle(command(null, ArticleStatus.PUBLISHED, List.of(1L)));
        var redis = context.getBean(StringRedisTemplate.class);
        clearInvocations(redis);
        var tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            content.saveArticle(new SaveArticleCommand(id, new ArticleRevision("rollback", null,
                    new ContentBody("replacement", ContentFormat.MARKDOWN), null, null, ArticleStatus.DRAFT, 0, 1, List.of(2L, 3L))));
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select title from article", String.class)).isEqualTo("DDD");
        assertThat(scalar("select tag_id from article_tag")).isEqualTo(1);
        verify(redis, never()).delete(anyString());
    }

    @Test
    void likeToggleAndSoftDeleteUseActualSql() {
        Long id = content.saveArticle(command(null, ArticleStatus.PUBLISHED, List.of()));
        assertThat(content.toggleLike(id)).isTrue();
        assertThat(scalar("select like_count from article")).isEqualTo(1);
        assertThat(content.toggleLike(id)).isFalse();
        assertThat(scalar("select like_count from article")).isZero();
        assertThat(scalar("select count(*) from article_like")).isZero();
        content.deleteArticle(id);
        assertThat(scalar("select deleted from article")).isEqualTo(1);
        assertThat(context.getBean(ArticleRepository.class).findById(new ArticleId(id))).isEmpty();
    }

    @Test
    void postingModerationAndSoftDeletePreserveReplyAndAuditFields() {
        Long articleId = content.saveArticle(command(null, ArticleStatus.PUBLISHED, List.of()));
        var interactions = context.getBean(InteractionApplicationFacade.class);
        Long id = interactions.save(new CommentSubmission(articleId, null, null, "owner comment"), "fixture-ip", "fixture-ua");
        assertThat(id).isPositive();
        assertThat(scalar("select status from comment")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select ip from comment", String.class)).isEqualTo("fixture-ip");
        interactions.adminAudit(id, 0);
        assertThat(scalar("select status from comment")).isZero();
        assertThatThrownBy(() -> interactions.adminAudit(id, 3)).isInstanceOf(DomainException.class);
        assertThat(interactions.adminList(0, 1, 10).records().get(0).content()).isEqualTo("owner comment");
        interactions.adminDelete(id);
        assertThat(scalar("select deleted from comment")).isEqualTo(1);
        assertThat(context.getBean(CommentRepository.class).findById(id)).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ComponentScan(basePackages = {"com.blogsystem.application.content", "com.blogsystem.application.interaction", "com.blogsystem.application.administration",
            "com.blogsystem.infrastructure.adapter.repository.content", "com.blogsystem.infrastructure.adapter.repository.interaction",
            "com.blogsystem.infrastructure.adapter.repository.administration",
            "com.blogsystem.infrastructure.rendering.content", "com.blogsystem.infrastructure.redis.content",
            "com.blogsystem.trigger.http.content", "com.blogsystem.trigger.http.interaction", "com.blogsystem.trigger.http.administration"},
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RedisCommentRateLimit.class))
    @Import({DomainRuntimeConfiguration.class, SpringDomainEventPublisher.class,
            com.blogsystem.app.config.content.SitePublicationConfiguration.class,
            com.blogsystem.app.config.asset.MediaReferenceWriteConfiguration.class})
    static class Fixture {
        @Bean
        com.fasterxml.jackson.databind.ObjectMapper json() {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        }

        @Bean
        DataSource dataSource() {
            var ds = new DriverManagerDataSource("jdbc:h2:mem:ddd_" + UUID.randomUUID() +
                    ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            var sql = new JdbcTemplate(ds);
            sql.execute("""
                    CREATE TABLE article (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    author_id bigint NOT NULL,title varchar(255) NOT NULL,summary varchar(500),content_md text,
                    content_html text,cover_url varchar(1000),category_id bigint,status integer DEFAULT 0,
                    is_top integer DEFAULT 0,is_comment_enabled integer DEFAULT 1,view_count integer DEFAULT 0,
                    like_count integer DEFAULT 0,collect_count integer DEFAULT 0,publish_time timestamp,
                    created_at timestamp DEFAULT CURRENT_TIMESTAMP,updated_at timestamp DEFAULT CURRENT_TIMESTAMP,deleted integer DEFAULT 0);
                    CREATE TABLE article_tag (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,article_id bigint NOT NULL,tag_id bigint NOT NULL,UNIQUE(article_id,tag_id));
                    CREATE TABLE article_like (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,article_id bigint NOT NULL,user_id bigint NOT NULL,UNIQUE(article_id,user_id));
                    CREATE TABLE category (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,name varchar(100),slug varchar(100),description text,sort integer,status integer,created_at timestamp,updated_at timestamp,deleted integer DEFAULT 0);
                    CREATE TABLE tag (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,name varchar(100),slug varchar(100),color varchar(30),sort integer,status integer,is_hot smallint NOT NULL DEFAULT 0,created_at timestamp,updated_at timestamp,deleted integer DEFAULT 0);
                    CREATE TABLE comment (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,article_id bigint NOT NULL,user_id bigint NOT NULL,
                    parent_id bigint DEFAULT 0,reply_to_user_id bigint,content text,status integer DEFAULT 0,ip varchar(100),user_agent varchar(100),
                    created_at timestamp DEFAULT CURRENT_TIMESTAMP,updated_at timestamp DEFAULT CURRENT_TIMESTAMP,deleted integer DEFAULT 0);
                    CREATE TABLE sys_user (id bigint PRIMARY KEY,deleted integer DEFAULT 0);
                    CREATE TABLE operation_log (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,user_id bigint,module varchar(100),action varchar(100),
                    content text,request_data text,ip varchar(100),user_agent varchar(100),created_at timestamp);
                    """);
            return ds;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig().setLogicDeleteField("deleted").setLogicDeleteValue("1").setLogicNotDeleteValue("0"));
            GlobalConfigUtils.setGlobalConfig(config, global);
            for (Class<?> mapper : List.of(ArticleMapper.class, ArticleTagMapper.class, ArticleLikeMapper.class,
                    CategoryMapper.class, TagMapper.class, CommentMapper.class, OwnerAccountMapper.class, OperationLogMapper.class))
                config.addMapper(mapper);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setConfiguration(config);
            factory.setGlobalConfig(global);
            factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor());
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        ArticleMapper articleMapper(SqlSessionTemplate sql) {
            return sql.getMapper(ArticleMapper.class);
        }

        @Bean
        ArticleTagMapper articleTagMapper(SqlSessionTemplate sql) {
            return sql.getMapper(ArticleTagMapper.class);
        }

        @Bean
        ArticleLikeMapper articleLikeMapper(SqlSessionTemplate sql) {
            return sql.getMapper(ArticleLikeMapper.class);
        }

        @Bean
        CategoryMapper categoryMapper(SqlSessionTemplate sql) {
            return sql.getMapper(CategoryMapper.class);
        }

        @Bean
        TagMapper tagMapper(SqlSessionTemplate sql) {
            return sql.getMapper(TagMapper.class);
        }

        @Bean
        CommentMapper commentMapper(SqlSessionTemplate sql) {
            return sql.getMapper(CommentMapper.class);
        }

        @Bean
        OwnerAccountMapper sysUserMapper(SqlSessionTemplate sql) {
            return sql.getMapper(OwnerAccountMapper.class);
        }

        @Bean
        OperationLogMapper operationLogMapper(SqlSessionTemplate sql) {
            return sql.getMapper(OperationLogMapper.class);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        CurrentActor actor() {
            return () -> 42L;
        }

        @Bean
        CommentRateLimit commentRateLimit() {
            return userId -> {
            };
        }

        @Bean
        StringRedisTemplate redis() {
            return mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        }

        @Bean
        RedisFailurePolicy redisFailurePolicy() {
            var policy = new RedisFailurePolicy();
            ReflectionTestUtils.setField(policy, "activeProfile", "dev");
            return policy;
        }
    }
}
