package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.infrastructure.adapter.repository.asset.*;
import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackRepository;
import com.blogsystem.infrastructure.adapter.repository.surprise.MybatisSurpriseVideoCatalogRepository;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedReferenceLinkGateway;
import com.blogsystem.infrastructure.mybatis.converter.asset.*;
import com.blogsystem.infrastructure.mybatis.mapper.asset.*;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Only generated schemas in blog_media_test; real Spring transactions and asset locks. */
final class CatalogMetadataWriteProtectionFixture implements AutoCloseable {
    static final String MUSIC_ID = "  Existing-视频-ID  ";
    static final Instant NOW = Instant.parse("2026-10-13T00:00:01.123456789Z");
    static final UUID AUDIO = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID COVER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID LYRICS = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID VIDEO = UUID.fromString("00000000-0000-0000-0000-000000000004");
    final MediaPostgresTestSupport.ScopedSchema schema;
    final DriverManagerDataSource dataSource;
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final SqlSessionTemplate session;
    final MybatisMusicTrackRepository music;
    final MybatisSurpriseVideoCatalogRepository videos;
    final MediaReferenceWriteRepository guard;
    final MediaReferenceWriteFixture.SqlTrace trace = new MediaReferenceWriteFixture.SqlTrace();

    CatalogMetadataWriteProtectionFixture() throws Exception {
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
            config.addMapper(MusicTrackMapper.class);
            config.addMapper(SurpriseVideoMapper.class);
            config.addInterceptor(trace);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(config);
            session = new SqlSessionTemplate(factory.getObject());
            var converter = new MediaAssetConverter();
            var assets = new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class), converter);
            var locks = new MybatisMediaDeletionAssetRepository(session.getMapper(MediaDeletionAssetMapper.class), new MediaDeletionConverter(converter));
            guard = new MybatisMediaReferenceWriteRepository(assets, locks,
                    new ImgBedReferenceLinkGateway(Set.of(URI.create("https://img.example")), "blog", 1048576, 10000),
                    "cloudflare_imgbed", new MediaObjectKey("blog"));
            music = new MybatisMusicTrackRepository(session.getMapper(MusicTrackMapper.class), guard);
            videos = new MybatisSurpriseVideoCatalogRepository(session.getMapper(SurpriseVideoMapper.class), guard);
            insertAsset(AUDIO, "AUDIO", "blog/music/audio.mp3");
            insertAsset(COVER, "IMAGE", "blog/images/cover.png");
            insertAsset(LYRICS, "LYRICS", "blog/music/lyrics.lrc");
            insertAsset(VIDEO, "VIDEO", "blog/surprise/video.mp4");
            jdbc.update("INSERT INTO music_track(id,title,artist,audio_asset_id,cover_asset_id,lyrics_asset_id,enabled,sort_order,version,created_at,updated_at) VALUES (?,'历史歌','歌手',?,?,?,true,0,7,'2026-10-13T00:00:00Z','2026-10-13T00:00:00Z')", MUSIC_ID, AUDIO, COVER, LYRICS);
            jdbc.update("INSERT INTO surprise_video(id,title,video_asset_id,enabled,sort_order,version,created_at,updated_at) VALUES ('video-1','历史视频',?,true,0,7,'2026-10-13T00:00:00Z','2026-10-13T00:00:00Z')", VIDEO);
        } catch (Exception failure) {
            schema.close();
            throw failure;
        }
    }

    void insertAsset(UUID id, String kind, String key) {
        jdbc.update("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES (?,'cloudflare_imgbed',?,?,?,'ACTIVE',now(),now())", id, key, kind, "resource");
    }
    @Override public void close() throws Exception { schema.close(); }
}
