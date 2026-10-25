package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaReferenceRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedMediaPublicUrlRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedReferenceLinkGateway;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaReferenceMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/** Minimal projection tables supplement existing media fixtures, never a business schema migration. */
final class MediaReferenceWorkflowFixture implements AutoCloseable {
    final MediaPublicationPinWorkflowFixture base;
    final JdbcTemplate jdbc;
    final MybatisMediaReferenceRepository references;

    MediaReferenceWorkflowFixture() throws Exception {
        base = new MediaPublicationPinWorkflowFixture();
        try {
            jdbc = base.jdbc;
            base.schema.execute("CREATE TABLE article(id bigint PRIMARY KEY,title text,status integer DEFAULT 0,deleted integer DEFAULT 0,cover_url text,content_md text,content_html text)");
            base.schema.execute("CREATE TABLE sys_user(id bigint PRIMARY KEY,password text,avatar text,status integer DEFAULT 0,deleted integer DEFAULT 0)");
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.setDefaultStatementTimeout(10);
            config.addMapper(MediaReferenceMapper.class);
            var factory = new SqlSessionFactoryBean(); factory.setDataSource(jdbc.getDataSource()); factory.setConfiguration(config);
            var session = new SqlSessionTemplate(factory.getObject());
            references = new MybatisMediaReferenceRepository(session.getMapper(MediaReferenceMapper.class),
                    new ImgBedReferenceLinkGateway(Set.of(URI.create("https://img.example"), URI.create("https://old.example")), "blog", 1048576, 10000), base.pins);
        } catch (Exception failure) { base.close(); throw failure; }
    }

    MediaAsset asset(String id) { return base.assets.findById(UUID.fromString(id)).orElseThrow().asset(); }
    String url(MediaAsset asset) { return new ImgBedMediaPublicUrlRepository(URI.create("https://img.example"), "blog").publicUrl(asset.provider(), asset.objectKey()); }
    void article(long id, String cover, String markdown, String html) {
        jdbc.update("INSERT INTO article(id,title,status,deleted,cover_url,content_md,content_html) VALUES (?, 'private title not a label',0,0,?,?,?)", id, cover, markdown, html);
    }
    void owner(long id, String avatar) {
        jdbc.update("INSERT INTO sys_user(id,password,avatar,status,deleted) VALUES (?,'test-only-not-a-real-secret',?,0,0)", id, avatar);
    }
    @Override public void close() throws Exception { base.close(); }
}
