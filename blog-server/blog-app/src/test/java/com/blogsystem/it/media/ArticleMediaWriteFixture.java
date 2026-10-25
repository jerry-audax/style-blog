package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.blogsystem.application.content.service.ArticleCommandService;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.infrastructure.adapter.repository.content.MybatisArticleRepository;
import com.blogsystem.infrastructure.event.SpringDomainEventPublisher;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleTagMapper;
import com.blogsystem.infrastructure.redis.content.HotArticleCache;
import com.blogsystem.infrastructure.redis.support.RedisFailurePolicy;
import com.blogsystem.infrastructure.rendering.content.ArticleBodyRenderer;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;

final class ArticleMediaWriteFixture implements AutoCloseable {
    final MediaReferenceWriteFixture base;
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final List<Object> events = new ArrayList<>();
    final HotArticleCache cache = new HotArticleCache(redis, new RedisFailurePolicy());
    final MybatisArticleRepository articles;
    final ArticleCommandService commands;
    private final SqlSessionTemplate session;

    ArticleMediaWriteFixture() throws Exception {
        base = new MediaReferenceWriteFixture();
        try {
            base.schema.execute("CREATE TABLE article(id bigserial PRIMARY KEY,author_id bigint NOT NULL,title text NOT NULL,summary text,content_md text,content_html text,cover_url text,category_id bigint,status integer NOT NULL DEFAULT 0,is_top integer DEFAULT 0,is_comment_enabled integer DEFAULT 1,view_count integer DEFAULT 0,like_count integer DEFAULT 0,collect_count integer DEFAULT 0,publish_time timestamp,created_at timestamp,updated_at timestamp,deleted integer DEFAULT 0)");
            base.schema.execute("CREATE TABLE tag(id bigint PRIMARY KEY)");
            base.schema.execute("INSERT INTO tag(id) VALUES (1),(2)");
            base.schema.execute("CREATE TABLE article_tag(id bigserial PRIMARY KEY,article_id bigint NOT NULL REFERENCES article(id),tag_id bigint NOT NULL REFERENCES tag(id))");
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true); config.setDefaultStatementTimeout(10);
            config.addMapper(ArticleMapper.class); config.addMapper(ArticleTagMapper.class);
            config.addInterceptor(base.trace);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(base.dataSource); factory.setConfiguration(config);
            session = new SqlSessionTemplate(factory.getObject());
            articles = repository(base.guard);
            commands = commands(articles);
        } catch (Exception failure) { base.close(); throw failure; }
    }

    MybatisArticleRepository repository(MediaReferenceWriteRepository protection) {
        return repository(protection, new ArticleBodyRenderer());
    }

    MybatisArticleRepository repository(MediaReferenceWriteRepository protection, ArticleBodyRenderer renderer) {
        return new MybatisArticleRepository(session.getMapper(ArticleMapper.class), session.getMapper(ArticleTagMapper.class), renderer, cache, protection);
    }

    ArticleCommandService commands(MybatisArticleRepository repository) {
        return new ArticleCommandService(repository, new SpringDomainEventPublisher(events::add));
    }

    @Override public void close() throws Exception { base.close(); }
}
