package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.application.asset.deletion.service.MediaDeletionApplicationService;
import com.blogsystem.domain.asset.deletion.adapter.repository.*;
import com.blogsystem.infrastructure.adapter.repository.asset.*;
import com.blogsystem.infrastructure.adapter.repository.music.*;
import com.blogsystem.infrastructure.adapter.repository.surprise.*;
import com.blogsystem.infrastructure.mybatis.converter.asset.*;
import com.blogsystem.infrastructure.mybatis.mapper.asset.*;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Clock;

/** All data and transactions belong to this randomly named schema; no provider is present. */
public final class MediaDeletionWorkflowFixture implements AutoCloseable {
    final MediaPostgresTestSupport.ScopedSchema schema;
    public final DataSource dataSource;
    public final TransactionTemplate tx;
    public final JdbcTemplate jdbc;
    public final MediaDeletionAssetRepository assets;
    public final MediaDeletionOperationRepository operations;
    public final MybatisMusicTrackRepository music;
    public final MybatisSurpriseVideoCatalogRepository videos;
    public final MediaDeletionApplicationService service;
    public MediaDeletionWorkflowFixture() throws Exception {
        schema=MediaRuntimeRepositoryIntegrationTest.fixture();
        try {
            MediaDeletionDdlIntegrationTest.migrate(schema);
            dataSource=MediaDeletionRepositoryIntegrationTest.dataSource(schema.name());
            jdbc=new JdbcTemplate(dataSource);jdbc.setQueryTimeout(10);
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setDefaultStatementTimeout(10);
            config.addMapper(MediaAssetMapper.class);config.addMapper(MediaDeletionAssetMapper.class);config.addMapper(MediaDeletionOperationMapper.class);config.addMapper(MusicTrackMapper.class);config.addMapper(SurpriseVideoMapper.class);
            var factory=new SqlSessionFactoryBean();factory.setDataSource(dataSource);factory.setConfiguration(config);
            var session=new SqlSessionTemplate(factory.getObject());
            tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));tx.setTimeout(10);
            var converter=new MediaDeletionConverter(new MediaAssetConverter());
            assets=new MybatisMediaDeletionAssetRepository(session.getMapper(MediaDeletionAssetMapper.class),converter);
            operations=new MybatisMediaDeletionOperationRepository(session.getMapper(MediaDeletionOperationMapper.class),converter);
            var guard=MediaRuntimeRepositoryIntegrationTest.guard(session);
            music=new MybatisMusicTrackRepository(session.getMapper(MusicTrackMapper.class),guard);videos=new MybatisSurpriseVideoCatalogRepository(session.getMapper(SurpriseVideoMapper.class),guard);
            service=new MediaDeletionApplicationService(assets,operations,new MybatisMusicTrackDeletionRepository(session.getMapper(MusicTrackMapper.class)),
                    new MybatisSurpriseVideoDeletionRepository(session.getMapper(SurpriseVideoMapper.class)),tx,Clock.systemUTC(),"local",true,()->true);
        }catch(Exception e){schema.close();throw e;}
    }
    @Override public void close() throws Exception {schema.close();}
}
