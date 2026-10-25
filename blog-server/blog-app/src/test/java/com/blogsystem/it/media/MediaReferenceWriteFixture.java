package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaDeletionAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaReferenceWriteRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedReferenceLinkGateway;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaDeletionConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Own random test schema only; no provider client, real credentials fixture, or business data. */
final class MediaReferenceWriteFixture implements AutoCloseable {
    final MediaPostgresTestSupport.ScopedSchema schema;
    final DriverManagerDataSource dataSource;
    final TransactionTemplate tx;
    final JdbcTemplate jdbc;
    final MybatisMediaAssetRepository assets;
    final MediaDeletionAssetRepository locks;
    final ImgBedReferenceLinkGateway links;
    final MediaReferenceWriteRepository guard;
    final SqlTrace trace = new SqlTrace();

    MediaReferenceWriteFixture() throws Exception {
        schema = MediaPostgresTestSupport.createSchema();
        try {
            schema.executeDdl();
            MediaDeletionDdlIntegrationTest.migrate(schema);
            dataSource = MediaDeletionRepositoryIntegrationTest.dataSource(schema.name());
            jdbc = new JdbcTemplate(dataSource);
            jdbc.setQueryTimeout(10);
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            tx.setTimeout(20);
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setDefaultStatementTimeout(10);
            config.addMapper(MediaAssetMapper.class);
            config.addMapper(MediaDeletionAssetMapper.class);
            config.addInterceptor(trace);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(config);
            var session = new SqlSessionTemplate(factory.getObject());
            var converter = new MediaAssetConverter();
            assets = new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class), converter);
            locks = new MybatisMediaDeletionAssetRepository(session.getMapper(MediaDeletionAssetMapper.class), new MediaDeletionConverter(converter));
            links = new ImgBedReferenceLinkGateway(Set.of(URI.create("https://img.example"), URI.create("https://old.example")), "blog", 1048576, 10000);
            guard = guard(locks);
        } catch (Exception failure) {
            schema.close();
            throw failure;
        }
    }

    MediaReferenceWriteRepository guard(MediaDeletionAssetRepository observedLocks) {
        return new MybatisMediaReferenceWriteRepository(assets, observedLocks, links, "cloudflare_imgbed", new MediaObjectKey("blog"));
    }

    void insert(UUID id, String key) {
        jdbc.update("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES (?,'cloudflare_imgbed',?,'IMAGE','image.png','ACTIVE',now(),now())", id, key);
    }

    void insertMany(List<UUID> ids) {
        jdbc.batchUpdate("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES (?,'cloudflare_imgbed',?,'IMAGE','image.png','ACTIVE',now(),now())",
                ids.stream().map(id -> new Object[]{id, "blog/images/" + id + ".png"}).toList());
    }

    @Override public void close() throws Exception { schema.close(); }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static final class SqlTrace implements Interceptor {
        final List<String> statements = new CopyOnWriteArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            statements.add(((StatementHandler) invocation.getTarget()).getBoundSql().getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
            return invocation.proceed();
        }
        long containing(String fragment) { return statements.stream().filter(sql -> sql.contains(fragment)).count(); }
    }
}
