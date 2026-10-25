package com.blogsystem.it.media;

import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaUploadOperationRepository;
import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackRepository;
import com.blogsystem.infrastructure.adapter.repository.surprise.MybatisSurpriseVideoCatalogRepository;
import com.blogsystem.infrastructure.mybatis.codec.asset.MediaUploadSnapshotCodec;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaUploadOperationConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaUploadOperationMapper;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;

import com.blogsystem.app.it.application.asset.upload.service.FakeMediaUploadStorageRepository;

import com.blogsystem.application.asset.upload.port.UploadBindingApplicationPort;
import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.application.asset.upload.service.MediaUploadLeaseRunner;

import com.blogsystem.domain.asset.metadata.gateway.MediaPublicUrlGateway;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadVideoSeed;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;

import java.io.InputStream;
import java.util.Arrays;

import com.blogsystem.it.media.MediaPostgresTestSupport.ScopedSchema;

import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.application.music.service.MusicUploadBindingApplicationService;
import com.blogsystem.application.surprise.service.SurpriseUploadBindingApplicationService;
import com.blogsystem.infrastructure.gateway.imgbed.asset.StreamingMediaUploadContentVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Same datasource for real repository and application transactions, guarded isolated PG only. */
public final class MediaUploadWorkflowFixture implements AutoCloseable {
    final MediaPostgresTestSupport.ScopedSchema schema;
    public final javax.sql.DataSource dataSource;
    public final TransactionTemplate tx;public final MediaUploadOperationRepository operations;
    public final MediaAssetRepository assets;public final MediaReferenceWriteRepository references;public final MybatisMusicTrackRepository music;public final MybatisSurpriseVideoCatalogRepository videos;
    public final FakeMediaUploadStorageRepository storage=new FakeMediaUploadStorageRepository();
    public MediaPublicUrlGateway urls=(provider,key)->"https://example.test/file/"+key.value();
    public final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
    public final List<UploadBindingApplicationPort> ports;
    public final MediaUploadLeaseRunner runner;
    public MediaUploadWorkflowFixture() throws Exception {
        schema=MediaRuntimeRepositoryIntegrationTest.fixture();
        try {
            MediaDeletionDdlIntegrationTest.migrate(schema);
            schema.executeDdl(com.blogsystem.test.RepositoryPaths.file("migration", "006_add_media_upload_operation_table.sql"));
            schema.executeDdl(com.blogsystem.test.RepositoryPaths.file("migration", "013_20261018_extend_media_upload_ledger_for_images.sql"));
            var ds=new DriverManagerDataSource(System.getenv("MEDIA_PG_TEST_URL")+"?currentSchema="+schema.name(),System.getenv("MEDIA_PG_TEST_USERNAME"),System.getenv("MEDIA_PG_TEST_PASSWORD"));
            dataSource=ds;
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(MediaUploadOperationMapper.class);config.addMapper(MediaAssetMapper.class);config.addMapper(MusicTrackMapper.class);config.addMapper(SurpriseVideoMapper.class);
            config.addMapper(com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper.class);
            var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
            var session=new SqlSessionTemplate(factory.getObject());tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            operations=new MybatisMediaUploadOperationRepository(session.getMapper(MediaUploadOperationMapper.class),new MediaUploadOperationConverter(new MediaUploadSnapshotCodec(new ObjectMapper())));
            assets=new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class),new MediaAssetConverter());
            references=MediaRuntimeRepositoryIntegrationTest.guard(session);
            music=new MybatisMusicTrackRepository(session.getMapper(MusicTrackMapper.class),references);videos=new MybatisSurpriseVideoCatalogRepository(session.getMapper(SurpriseVideoMapper.class),references);
            ports=List.of(new MusicUploadBindingApplicationService(music,assets),new SurpriseUploadBindingApplicationService(videos,assets,UUID::randomUUID));
            runner=new MediaUploadLeaseRunner(operations,tx,executor);
        } catch(Exception e){schema.close();executor.shutdownNow();throw e;}
    }
    public MediaUploadApplicationService service(){return service(operations,assets,ports,tx);}
    public MediaUploadApplicationService service(MediaUploadOperationRepository ops,MediaAssetRepository assetPort,List<UploadBindingApplicationPort> bindings,TransactionOperations transactions){
        return new MediaUploadApplicationService(ops,assetPort,bindings,storage,new StreamingMediaUploadContentVerifier(),transactions,new MediaUploadLeaseRunner(ops,transactions,executor),Clock.systemUTC(),UUID::randomUUID,"cloudflare_imgbed",new MediaObjectKey("blog"),urls);
    }
    public static MediaUploadRequest request(long bytes,MediaUploadSlot slot,long version) throws Exception {
        String hash;if(bytes==209715200)hash="72abf2ca8f36943ebe2e49ca3a51d409ca5f0bfcffab6c9d25643c17c32889da";
        else{var digest=MessageDigest.getInstance("SHA-256");byte[] block=new byte[65536];for(long remaining=bytes;remaining>0;remaining-=Math.min(remaining,block.length))digest.update(block,0,(int)Math.min(remaining,block.length));hash=HexFormat.of().formatHex(digest.digest());}
        boolean video=slot==MediaUploadSlot.VIDEO;
        return new MediaUploadRequest(UUID.randomUUID(),video?MediaUploadTargetType.SURPRISE_CREATE:MediaUploadTargetType.MUSIC_TRACK,video?null:"  Existing-视频-ID  ",slot,video?null:version,new MediaUploadFile(video?"video.mp4":"song.mp3",video?"video/mp4":"audio/mpeg",bytes,hash),video?new MediaUploadVideoSeed("测试视频",true,0):null);
    }
    public static MediaUploadContent zeros(long bytes){return new MediaUploadContent(){
        public long size(){return bytes;}public String fileName(){return bytes>104857600?"video.mp4":"song.mp3";}public String mediaType(){return bytes>104857600?"video/mp4":"audio/mpeg";}
        public InputStream openStream(){return new InputStream(){long remaining=bytes;
            public int read(){if(remaining==0)return -1;remaining--;return 0;}
            public int read(byte[] b,int off,int len){if(remaining==0)return -1;int n=(int)Math.min(len,remaining);Arrays.fill(b,off,off+n,(byte)0);remaining-=n;return n;}
        };}
    };}
    public void execute(String sql) throws Exception {schema.execute(sql);}
    public void close() throws Exception {executor.shutdownNow();schema.close();}
}
