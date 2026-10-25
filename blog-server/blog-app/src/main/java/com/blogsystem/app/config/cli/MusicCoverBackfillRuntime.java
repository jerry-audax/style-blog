package com.blogsystem.app.config.cli;

import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.application.asset.upload.service.MediaUploadLeaseRunner;
import com.blogsystem.application.music.service.MusicCoverBackfillApplicationService;
import com.blogsystem.application.music.service.MusicCoverBackfillBatchApplicationService;
import com.blogsystem.application.music.service.MusicUploadBindingApplicationService;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaUploadOperationRepository;
import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackRepository;
import com.blogsystem.infrastructure.configuration.asset.MediaUploadProviderSettings;
import com.blogsystem.infrastructure.gateway.imgbed.asset.CloudflareMediaUploadRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedMediaPublicUrlRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.StreamingMediaUploadContentVerifier;
import com.blogsystem.infrastructure.gateway.netease.NeteaseSongArtworkDownloadRepository;
import com.blogsystem.infrastructure.gateway.netease.NeteaseSongArtworkRepository;
import com.blogsystem.infrastructure.mybatis.codec.asset.MediaUploadSnapshotCodec;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaUploadOperationConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaUploadOperationMapper;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.schema.MediaCatalogSchemaVerifier;
import com.blogsystem.infrastructure.mybatis.schema.MediaUploadSchemaVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Explicit offline composition: no web server, AI, Redis, auto-DDL or directory discovery. */
public final class MusicCoverBackfillRuntime implements AutoCloseable {
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
    private final MusicCoverBackfillBatchApplicationService batch;

    public MusicCoverBackfillRuntime(Map<String,String> env) throws Exception {
        String url=env.get("MEDIA_IMPORT_DB_URL");
        if(url==null || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5432/blog_system"))
            throw new IllegalArgumentException("Only local blog_system is allowed");
        try {
            var ds=new DriverManagerDataSource(url,env.get("MEDIA_IMPORT_DB_USERNAME"),env.get("MEDIA_IMPORT_DB_PASSWORD"));
            new MediaCatalogSchemaVerifier(ds).verify();
            new MediaUploadSchemaVerifier(ds).verify();
            var config=new Configuration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(MediaAssetMapper.class); config.addMapper(MusicTrackMapper.class); config.addMapper(MediaUploadOperationMapper.class);
            config.addMapper(com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper.class);
            var factory=new SqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
            factory.setTransactionFactory(new SpringManagedTransactionFactory());
            var session=new SqlSessionTemplate(factory.getObject());
            var manager=new DataSourceTransactionManager(ds); manager.setRollbackOnCommitFailure(true);
            var transactions=new TransactionTemplate(manager);
            var assets=new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class),new MediaAssetConverter());
            var json=new ObjectMapper();
            var operations=new MybatisMediaUploadOperationRepository(session.getMapper(MediaUploadOperationMapper.class),
                    new MediaUploadOperationConverter(new MediaUploadSnapshotCodec(json)));
            var settings=new MediaUploadProviderSettings(env.get("IMGBED_BASE_URL"),env.get("IMGBED_API_TOKEN"),
                    env.getOrDefault("IMGBED_UPLOAD_CHANNEL","telegram"),env.get("IMGBED_CHANNEL_NAME"),
                    env.getOrDefault("IMGBED_ROOT_FOLDER","blog"),Duration.ofSeconds(30),Duration.ofSeconds(30),Duration.ofSeconds(60),Duration.ofSeconds(120));
            var urls=new ImgBedMediaPublicUrlRepository(settings.baseUrl,settings.rootFolder);
            var tracks=buildTracks(session,assets,settings.baseUrl,settings.rootFolder);
            var uploads=new MediaUploadApplicationService(operations,assets,List.of(new MusicUploadBindingApplicationService(tracks,assets)),
                    new CloudflareMediaUploadRepository(settings,json),new StreamingMediaUploadContentVerifier(),transactions,
                    new MediaUploadLeaseRunner(operations,transactions,scheduler),Clock.systemUTC(),UUID::randomUUID,
                    "cloudflare_imgbed",new MediaObjectKey(settings.rootFolder),urls);
            var covers=new MusicCoverBackfillApplicationService(tracks,
                    new NeteaseSongArtworkDownloadRepository(new NeteaseSongArtworkRepository(json)),operations,uploads);
            batch=new MusicCoverBackfillBatchApplicationService(tracks,covers);
        } catch(Exception error) { scheduler.shutdownNow(); throw error; }
    }

    public MusicCoverBackfillBatchApplicationService batch() { return batch; }
    static com.blogsystem.domain.music.adapter.repository.MusicTrackRepository buildTracks(
            SqlSessionTemplate session, com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository assets,
            java.net.URI baseUrl, String rootFolder) {
        var locks = new com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaDeletionAssetRepository(
                session.getMapper(com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper.class),
                new com.blogsystem.infrastructure.mybatis.converter.asset.MediaDeletionConverter(new MediaAssetConverter()));
        var limits = new com.blogsystem.app.config.properties.MediaReferenceProperties();
        var links = new com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedReferenceLinkGateway(
                java.util.Set.of(baseUrl), rootFolder, limits.getMaxContentChars(), limits.getMaxLinks());
        var references = new com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaReferenceWriteRepository(
                assets, locks, links, "cloudflare_imgbed", new MediaObjectKey(rootFolder));
        return new MybatisMusicTrackRepository(session.getMapper(MusicTrackMapper.class), references);
    }
    public void close() { scheduler.shutdownNow(); }
}
