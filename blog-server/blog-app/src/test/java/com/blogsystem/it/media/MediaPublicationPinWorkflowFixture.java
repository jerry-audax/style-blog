package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.application.asset.publication.service.MediaPublicationPinApplicationService;
import com.blogsystem.domain.asset.deletion.adapter.repository.MediaDeletionAssetRepository;
import com.blogsystem.domain.asset.publication.adapter.repository.MediaPublicationPinRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaDeletionAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaPublicationPinRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaDeletionConverter;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaPublicationPinConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaPublicationPinMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZoneOffset;

final class MediaPublicationPinWorkflowFixture implements AutoCloseable {
    final MediaPostgresTestSupport.ScopedSchema schema;
    final TransactionTemplate tx;
    final JdbcTemplate jdbc;
    final MediaDeletionAssetRepository assets;
    final MediaPublicationPinRepository pins;
    final Clock clock = Clock.fixed(MediaPublicationPinRepositoryIntegrationTest.NOW, ZoneOffset.UTC);
    final MediaPublicationPinApplicationService service;

    MediaPublicationPinWorkflowFixture() throws Exception {
        schema = MediaRuntimeRepositoryIntegrationTest.fixture();
        try {
            MediaDeletionDdlIntegrationTest.migrate(schema);
            MediaPublicationPinDdlIntegrationTest.migrate(schema);
            var dataSource = MediaDeletionRepositoryIntegrationTest.dataSource(schema.name());
            jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(10);
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.setDefaultStatementTimeout(10);
            config.addMapper(MediaDeletionAssetMapper.class); config.addMapper(MediaPublicationPinMapper.class);
            var factory = new SqlSessionFactoryBean(); factory.setDataSource(dataSource); factory.setConfiguration(config);
            var session = new SqlSessionTemplate(factory.getObject());
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource)); tx.setTimeout(10);
            assets = new MybatisMediaDeletionAssetRepository(session.getMapper(MediaDeletionAssetMapper.class), new MediaDeletionConverter(new MediaAssetConverter()));
            pins = new MybatisMediaPublicationPinRepository(session.getMapper(MediaPublicationPinMapper.class), new MediaPublicationPinConverter());
            service = service(pins);
        } catch (Exception failure) { schema.close(); throw failure; }
    }

    MediaPublicationPinApplicationService service(MediaPublicationPinRepository repository) {
        return new MediaPublicationPinApplicationService(repository, assets, tx, clock);
    }

    @Override public void close() throws Exception { schema.close(); }
}
