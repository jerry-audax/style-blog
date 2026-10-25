package com.blogsystem.it.media;

import com.blogsystem.trigger.http.music.HostedMusicPublicController;
import com.blogsystem.trigger.http.music.MusicAdminController;
import com.blogsystem.trigger.http.music.MusicExceptionHandler;
import com.blogsystem.trigger.http.surprise.SurpriseAdminController;
import com.blogsystem.trigger.http.surprise.SurpriseExceptionHandler;
import com.blogsystem.trigger.http.surprise.SurprisePublicController;

import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackRepository;
import com.blogsystem.infrastructure.adapter.repository.surprise.MybatisSurpriseVideoCatalogRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;

import com.blogsystem.application.music.service.MusicApplicationService;
import com.blogsystem.application.music.service.PersistentMusicCatalogApplicationService;

import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.domain.music.model.MusicTrackMetadata;
import com.blogsystem.domain.music.model.MusicTrackSnapshot;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.application.asset.metadata.service.MediaAssetQueryApplicationService;
import com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedMediaPublicUrlRepository;
import com.blogsystem.application.surprise.service.PersistentSurpriseVideoApplicationService;
import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import com.blogsystem.infrastructure.mybatis.schema.MediaCatalogSchemaVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.nullValue;

class MediaCatalogRuntimeApiIntegrationTest {
    @Test void realPgVideoApiPreservesAssetBindingAndBlocksResourceWrites() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.protectedFixture();var auth=new CatalogMvcTestSupport()) {
            var ds=dataSource(schema.name());var session=session(ds);
            var assets=new MediaAssetQueryApplicationService(new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class),new MediaAssetConverter()),new ImgBedMediaPublicUrlRepository(URI.create("https://example.test"),"blog"));
            var videos=new MybatisSurpriseVideoCatalogRepository(session.getMapper(SurpriseVideoMapper.class),MediaRuntimeRepositoryIntegrationTest.guard(session));
            var service=new PersistentSurpriseVideoApplicationService(videos,assets,Clock.fixed(MediaRuntimeRepositoryIntegrationTest.NOW,ZoneOffset.UTC),new TransactionTemplate(new DataSourceTransactionManager(ds)));
            var mvc=auth.build(new Object[]{new SurpriseAdminController(service),new SurprisePublicController(service)},new SurpriseExceptionHandler());
            String token=auth.token(1);
            mvc.perform(put("/api/admin/surprise/videos/video-1").header("Authorization",token).contentType("application/json").content("{\"title\":\"新视频\",\"enabled\":false,\"expectedVersion\":7}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(8)).andExpect(jsonPath("$.data.size").value(nullValue()));
            mvc.perform(put("/api/admin/surprise/videos/video-1").header("Authorization",token).contentType("application/json").content("{\"title\":\"旧编辑\",\"expectedVersion\":7}"))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/surprise/random")).andExpect(status().isServiceUnavailable());
            mvc.perform(delete("/api/admin/surprise/videos/video-1").header("Authorization",token)).andExpect(status().isServiceUnavailable());
            assertEquals(UUID.fromString(MediaRuntimeRepositoryIntegrationTest.VIDEO),videos.findSnapshotById("video-1").orElseThrow().video().videoAssetId());
            assertEquals(1,new JdbcTemplate(ds).queryForObject("SELECT count(*) FROM surprise_video",Integer.class));
        }
    }
    @Test void realPgApiReadsWithoutSideEffectsAndRejectsStaleClientAndDuplicate() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.protectedFixture();var auth=new CatalogMvcTestSupport()) {
            schema.execute("UPDATE music_track SET source_id='163',cover_asset_id=NULL");
            var ds=dataSource(schema.name());var jdbc=new JdbcTemplate(ds);var session=session(ds);
            var tracks=new MybatisMusicTrackRepository(session.getMapper(MusicTrackMapper.class),MediaRuntimeRepositoryIntegrationTest.guard(session));
            var assets=new MediaAssetQueryApplicationService(new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class),new MediaAssetConverter()),
                    new ImgBedMediaPublicUrlRepository(URI.create("https://example.test"),"blog"));
            int[] artworkCalls={0};SongArtworkGateway artwork=ids->{artworkCalls[0]++;assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return Map.of("163","https://example.test/art.jpg");};
            var service=new PersistentMusicCatalogApplicationService(tracks,assets,snapshots(),artwork,new MusicPolicy("939817038"),Clock.fixed(MediaRuntimeRepositoryIntegrationTest.NOW,ZoneOffset.UTC),new TransactionTemplate(new DataSourceTransactionManager(ds)));
            var mvc=auth.build(new Object[]{new MusicAdminController(mock(MusicApplicationService.class),service),new HostedMusicPublicController(service)},new MusicExceptionHandler());
            String token=auth.token(1);String fingerprint=fingerprint(jdbc);
            mvc.perform(get("/api/music/hosted-playlist")).andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("imgbed")).andExpect(jsonPath("$.data.songs[0].id").value("  Existing-视频-ID  ")).andExpect(jsonPath("$.data.songs[0].cover").value(""));
            mvc.perform(get("/api/admin/music/catalog").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].audio.size").value(nullValue()));
            assertEquals(fingerprint,fingerprint(jdbc));
            mvc.perform(put("/api/admin/music/tracks/{id}","  Existing-视频-ID  ").header("Authorization",token).contentType("application/json")
                    .content("{\"title\":\"新标题\",\"artist\":\"歌手\",\"sourceId\":\"163\",\"expectedVersion\":7}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(8));
            mvc.perform(put("/api/admin/music/tracks/{id}","  Existing-视频-ID  ").header("Authorization",token).contentType("application/json")
                    .content("{\"title\":\"陈旧覆盖\",\"expectedVersion\":7}")) .andExpect(status().isConflict());
            mvc.perform(post("/api/admin/music/tracks").header("Authorization",token).contentType("application/json").content("{\"id\":\"new-track\",\"title\":\"新歌\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
            mvc.perform(post("/api/admin/music/tracks").header("Authorization",token).contentType("application/json").content("{\"id\":\"new-track\",\"title\":\"重复\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(delete("/api/admin/music/tracks/new-track").header("Authorization",token)).andExpect(status().isServiceUnavailable());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM music_track",Integer.class));
            assertEquals("新标题",tracks.findSnapshotById("  Existing-视频-ID  ").orElseThrow().track().title());
            assertEquals(0,artworkCalls[0]);assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM media_asset",Integer.class));
        }
    }
    @Test void springTransactionRollsBackWhenRepositoryFailsAfterSqlUpdate() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.protectedFixture()) {
            var ds=dataSource(schema.name());var session=session(ds);
            var real=new MybatisMusicTrackRepository(session.getMapper(MusicTrackMapper.class),MediaRuntimeRepositoryIntegrationTest.guard(session));
            MusicTrackRepository failsAfterWrite=new MusicTrackRepository(){
                public List<MusicTrackSnapshot> findAdminList(){return real.findAdminList();}
                public Optional<MusicTrackSnapshot> findSnapshotById(String id){return real.findSnapshotById(id);}
                public List<MusicTrackSnapshot> findPublicCandidates(int limit){return real.findPublicCandidates(limit);}
                public MusicTrackSnapshot insertNewMusicTrack(PersistedMusicTrack t,Instant now){return real.insertNewMusicTrack(t,now);}
                public MusicTrackSnapshot updateMetadata(String id,MusicTrackMetadata m,long v,Instant now){real.updateMetadata(id,m,v,now);throw new MusicCatalogException(MusicCatalogException.Reason.UNAVAILABLE);}};
            var assets=new MediaAssetQueryApplicationService(new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class),new MediaAssetConverter()),new ImgBedMediaPublicUrlRepository(URI.create("https://example.test"),"blog"));
            var service=new PersistentMusicCatalogApplicationService(failsAfterWrite,assets,snapshots(),ids->Map.of(),new MusicPolicy("939817038"),Clock.fixed(MediaRuntimeRepositoryIntegrationTest.NOW,ZoneOffset.UTC),new TransactionTemplate(new DataSourceTransactionManager(ds)));
            assertThrows(MusicCatalogException.class,()->service.updateMetadata("  Existing-视频-ID  ","应该回滚","",null,true,0,7L));
            var unchanged=real.findSnapshotById("  Existing-视频-ID  ").orElseThrow();assertEquals(7,unchanged.version());assertEquals("历史歌",unchanged.track().title());
        }
    }
    @Test void schemaVerifierOnlyReadsAndRejectsMissingOrMistypedMediaColumns() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.fixture()) {
            var ds=dataSource(schema.name());var jdbc=new JdbcTemplate(ds);var verifier=new MediaCatalogSchemaVerifier(ds);
            String before=fingerprint(jdbc);verifier.verify();assertEquals(before,fingerprint(jdbc));
            schema.execute("ALTER TABLE media_asset DROP COLUMN file_size");
            var missing=assertThrows(IllegalStateException.class,verifier::verify);assertFalse(missing.getMessage().contains("SELECT"));
            schema.execute("ALTER TABLE media_asset ADD COLUMN file_size INTEGER");assertThrows(IllegalStateException.class,verifier::verify);
        }
    }
    static DriverManagerDataSource dataSource(String schema){return new DriverManagerDataSource(System.getenv("MEDIA_PG_TEST_URL")+"?currentSchema="+schema,System.getenv("MEDIA_PG_TEST_USERNAME"),System.getenv("MEDIA_PG_TEST_PASSWORD"));}
    static SqlSessionTemplate session(DriverManagerDataSource ds) throws Exception {
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(MusicTrackMapper.class);config.addMapper(SurpriseVideoMapper.class);config.addMapper(MediaAssetMapper.class);
        config.addMapper(com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);return new SqlSessionTemplate(factory.getObject());
    }
    static PlaylistSnapshotRepository snapshots(){return new PlaylistSnapshotRepository(){public Optional<MusicPlaylist> find(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return Optional.empty();}public void save(MusicPlaylist p){throw new AssertionError("No business JSON write");}public void clear(){throw new AssertionError();}};}
    static String fingerprint(JdbcTemplate jdbc){StringBuilder value=new StringBuilder();for(String table:List.of("media_asset","music_track","surprise_video"))value.append(jdbc.queryForObject("SELECT md5(coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY id),'')) FROM "+table+" t",String.class));return value.toString();}
}
