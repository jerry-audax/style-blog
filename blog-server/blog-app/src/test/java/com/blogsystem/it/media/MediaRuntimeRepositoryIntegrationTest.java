package com.blogsystem.it.media;

import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.adapter.repository.music.MybatisMusicTrackRepository;
import com.blogsystem.infrastructure.adapter.repository.surprise.MybatisSurpriseVideoCatalogRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import com.blogsystem.infrastructure.mybatis.mapper.music.MusicTrackMapper;
import com.blogsystem.infrastructure.mybatis.mapper.surprise.SurpriseVideoMapper;

import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicTrackMetadata;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.domain.surprise.model.SurpriseCatalogException;
import com.blogsystem.domain.surprise.model.SurpriseVideoMetadata;

import com.blogsystem.it.media.MediaPostgresTestSupport.ScopedSchema;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assertions;
import org.mybatis.spring.SqlSessionFactoryBean;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MediaRuntimeRepositoryIntegrationTest {
    static final String AUDIO="00000000-0000-0000-0000-000000000001";
    static final String IMAGE="00000000-0000-0000-0000-000000000002";
    static final String VIDEO="00000000-0000-0000-0000-000000000003";
    static final Instant NOW=Instant.parse("2026-10-13T00:00:01.123456789Z");

    @Test void bindsOnlyRequestedSlotAtVersionZero() throws Exception {
        for(var kind:MusicMediaKind.values()) {
            try(var schema=protectedFixture()) {
                schema.execute("UPDATE music_track SET version=0 WHERE id='  Existing-视频-ID  '");
                UUID id=UUID.randomUUID();String mediaKind=switch(kind){case AUDIO->"AUDIO";case COVER->"IMAGE";case LYRICS->"LYRICS";};
                schema.execute("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES ('"+id+"','cloudflare_imgbed','blog/managed-uploads/"+id+"/resource','"+mediaKind+"','resource','ACTIVE',now(),now())");
                var r=music(springSession(schema.name()));var tx=transactions(schema.name());var before=r.findSnapshotById("  Existing-视频-ID  ").orElseThrow();
                UUID old=switch(kind){case AUDIO->before.track().audioAssetId();case COVER->before.track().coverAssetId();case LYRICS->before.track().lyricsAssetId();};
                var saved=tx.execute(s->r.bindMedia(before.track().id(),kind,id,0,old,NOW));assertEquals(1,saved.version());
                assertEquals(before.track().title(),saved.track().title());assertEquals(before.createdAt(),saved.createdAt());
                assertEquals(kind==MusicMediaKind.AUDIO?id:before.track().audioAssetId(),saved.track().audioAssetId());
                assertEquals(kind==MusicMediaKind.COVER?id:before.track().coverAssetId(),saved.track().coverAssetId());
                assertEquals(kind==MusicMediaKind.LYRICS?id:before.track().lyricsAssetId(),saved.track().lyricsAssetId());
            }
        }
    }
    @Test void nullOldAssetMatchesButDifferentOldAssetLoses() throws Exception {
        try(var schema=protectedFixture()) {
            UUID id=UUID.randomUUID();schema.execute("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES ('"+id+"','cloudflare_imgbed','blog/lyrics/"+id+"','LYRICS','lyrics','ACTIVE',now(),now())");
            var r=music(springSession(schema.name()));var tx=transactions(schema.name());
            assertEquals(8,tx.execute(s->r.bindMedia("  Existing-视频-ID  ",MusicMediaKind.LYRICS,id,7,null,NOW)).version());
            assertEquals(MusicCatalogException.Reason.CONFLICT,assertThrows(MusicCatalogException.class,()->tx.execute(s->r.bindMedia("  Existing-视频-ID  ",MusicMediaKind.LYRICS,id,8,null,NOW))).reason());
            assertEquals(id,r.findSnapshotById("  Existing-视频-ID  ").orElseThrow().track().lyricsAssetId());
        }
    }
    @Test void staleOrDeletedTargetKeepsAllFields() throws Exception {
        try(var schema=protectedFixture()) {
            var r=music(springSession(schema.name()));var tx=transactions(schema.name());var before=r.findSnapshotById("  Existing-视频-ID  ").orElseThrow();
            assertEquals(MusicCatalogException.Reason.CONFLICT,assertThrows(MusicCatalogException.class,()->tx.execute(s->r.bindMedia(before.track().id(),MusicMediaKind.AUDIO,UUID.fromString(AUDIO),6,UUID.fromString(AUDIO),NOW))).reason());
            assertEquals(before,r.findSnapshotById(before.track().id()).orElseThrow());
            schema.execute("UPDATE music_track SET deleted_at=now() WHERE id='  Existing-视频-ID  '");
            assertEquals(MusicCatalogException.Reason.NOT_FOUND,assertThrows(MusicCatalogException.class,()->tx.execute(s->r.bindMedia(before.track().id(),MusicMediaKind.AUDIO,UUID.fromString(AUDIO),7,UUID.fromString(AUDIO),NOW))).reason());
        }
    }
    @Test void wrongKindOrInactiveAssetNeverBinds() throws Exception {
        try(var schema=protectedFixture()) {
            var r=music(springSession(schema.name()));var tx=transactions(schema.name());var before=r.findSnapshotById("  Existing-视频-ID  ").orElseThrow();
            assertEquals(MusicCatalogException.Reason.CONFLICT,assertThrows(MusicCatalogException.class,()->tx.execute(s->r.bindMedia(before.track().id(),MusicMediaKind.AUDIO,UUID.fromString(IMAGE),7,UUID.fromString(AUDIO),NOW))).reason());
            schema.execute("UPDATE media_asset SET status='DELETE_PENDING' WHERE id='"+AUDIO+"'");
            assertEquals(MusicCatalogException.Reason.CONFLICT,assertThrows(MusicCatalogException.class,()->tx.execute(s->r.bindMedia(before.track().id(),MusicMediaKind.AUDIO,UUID.fromString(AUDIO),7,UUID.fromString(AUDIO),NOW))).reason());
            assertEquals(before,r.findSnapshotById(before.track().id()).orElseThrow());
        }
    }
    @Test void videoUsesPreallocatedIdAndDuplicateIsConflict() throws Exception {
        try(var schema=protectedFixture()) {
            var r=videos(springSession(schema.name()));var tx=transactions(schema.name());var value=new PersistedSurpriseVideo("stable-new-video","新视频",UUID.fromString(VIDEO),false,15);
            var saved=tx.execute(s->r.insertNewVideo(value,NOW));assertEquals(value,saved.video());assertEquals(0,saved.version());
            assertEquals(SurpriseCatalogException.Reason.CONFLICT,assertThrows(SurpriseCatalogException.class,()->tx.execute(s->r.insertNewVideo(value,NOW))).reason());
            assertEquals(saved,r.findSnapshotById(value.id()).orElseThrow());
            assertThrows(SurpriseCatalogException.class,()->tx.execute(s->r.insertNewVideo(new PersistedSurpriseVideo("wrong-kind","错类型",UUID.fromString(AUDIO),true,0),NOW)));
            assertTrue(r.findSnapshotById("wrong-kind").isEmpty());
            schema.execute("UPDATE media_asset SET status='DELETE_PENDING' WHERE id='"+VIDEO+"'");
            assertThrows(SurpriseCatalogException.class,()->tx.execute(s->r.insertNewVideo(new PersistedSurpriseVideo("inactive-video","不可用资源",UUID.fromString(VIDEO),true,0),NOW)));
            assertTrue(r.findSnapshotById("inactive-video").isEmpty());
        }
    }

    @Test void batchAssetReadsPreserveInactiveUnknownAndZero() throws Exception {
        try(var schema=fixture()) {
            schema.execute("UPDATE media_asset SET status='DELETE_PENDING' WHERE id='"+AUDIO+"'; UPDATE media_asset SET file_size=0 WHERE id='"+IMAGE+"'");
            try(var session=factory(schema.name()).openSession(false)) {
                var repo=new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class),new MediaAssetConverter());
                assertTrue(repo.findByIds(java.util.Set.of()).isEmpty());
                var rows=repo.findByIds(java.util.Set.of(UUID.fromString(AUDIO),UUID.fromString(IMAGE),UUID.randomUUID()));
                assertEquals(2,rows.size());
                var audio=rows.stream().filter(a->a.id().equals(UUID.fromString(AUDIO))).findFirst().orElseThrow();
                assertEquals("blog/music/中文+100%.mp3",audio.objectKey().value());assertNull(audio.metadata().fileSize());
                assertEquals(com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus.DELETE_PENDING,audio.status());
                assertEquals(0L,rows.stream().filter(a->a.id().equals(UUID.fromString(IMAGE))).findFirst().orElseThrow().metadata().fileSize());
            }
        }
    }

    @Test void mapperReadsNullableColumnsWithExplicitUuidMapping() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            var row=session.getMapper(MusicTrackMapper.class).selectById("  Existing-视频-ID  ");
            assertEquals(UUID.fromString(AUDIO),row.audioAssetId()); assertNull(row.sourceId());
            assertNull(row.lyricsAssetId()); assertEquals(7,row.version());
        }
    }

    @Test void oneStaleUpdateLosesWithoutChangingReferences() throws Exception {
        try(var schema=protectedFixture()) {
            var session=springSession(schema.name());
            var repo=music(session);
            var tx=transactions(schema.name());
            var before=repo.findSnapshotById("  Existing-视频-ID  ").orElseThrow();
            var saved=tx.execute(s->repo.updateMetadata(before.track().id(),new MusicTrackMetadata(" 新名称 "," 新歌手 ",null,false,9),7,NOW));
            assertEquals(8,saved.version()); assertEquals("新名称",saved.track().title());
            assertEquals(UUID.fromString(AUDIO),saved.track().audioAssetId());
            assertEquals(UUID.fromString(IMAGE),saved.track().coverAssetId());
            assertEquals(before.createdAt(),saved.createdAt()); assertNull(saved.track().lyricsAssetId());
            assertEquals(Instant.parse("2026-10-13T00:00:01.123456Z"),saved.updatedAt());
            assertEquals(MusicCatalogException.Reason.CONFLICT,assertThrows(MusicCatalogException.class,
                    ()->tx.execute(s->repo.updateMetadata(before.track().id(),new MusicTrackMetadata("覆盖","",null,true,0),7,NOW))).reason());
            assertEquals("新名称",repo.findSnapshotById(before.track().id()).orElseThrow().track().title());
        }
    }

    @Test void doesNotRestoreDeletedRow() throws Exception {
        try(var schema=fixture()) {
            schema.execute("UPDATE music_track SET deleted_at=now(); UPDATE surprise_video SET deleted_at=now()");
            {
                var session=springSession(schema.name());
                var repo=music(session); var videos=videos(session);
                var tx=transactions(schema.name());
                assertTrue(repo.findAdminList().isEmpty()); assertTrue(repo.findPublicCandidates(2001).isEmpty());
                assertTrue(repo.findSnapshotById("  Existing-视频-ID  ").isEmpty());
                assertEquals(MusicCatalogException.Reason.NOT_FOUND,assertThrows(MusicCatalogException.class,
                        ()->tx.execute(s->repo.updateMetadata("  Existing-视频-ID  ",new MusicTrackMetadata("x","",null,true,0),7,NOW))).reason());
                assertTrue(videos.findAdminList().isEmpty()); assertTrue(videos.findPublicCandidates().isEmpty());
                assertEquals(SurpriseCatalogException.Reason.NOT_FOUND,assertThrows(SurpriseCatalogException.class,
                        ()->tx.execute(s->videos.updateMetadata("video-1",new SurpriseVideoMetadata("x",true,0),7,NOW))).reason());
            }
        }
    }

    @Test void newDuplicateDoesNotOverwrite() throws Exception {
        try(var schema=fixture()) {
            var factory=factory(schema.name());
            try(var session=factory.openSession(false)) {
                var repo=music(session);
                var created=repo.insertNewMusicTrack(new PersistedMusicTrack("new","新歌","",null,null,null,null,null,true,0),NOW);
                assertEquals(0,created.version()); assertNull(created.track().audioAssetId()); session.commit();
                assertEquals(MusicCatalogException.Reason.DUPLICATE,assertThrows(MusicCatalogException.class,
                        ()->repo.insertNewMusicTrack(new PersistedMusicTrack("new","覆盖","",null,null,null,null,null,true,0),NOW)).reason());
                session.rollback();
            }
            try(var session=factory.openSession(false)) {
                assertEquals("新歌",music(session).findSnapshotById("new").orElseThrow().track().title());
                assertEquals(2,music(session).findAdminList().size());
            }
        }
    }

    @Test void publicCandidatesFilterBeforeLimit() throws Exception {
        try(var schema=fixture()) {
            schema.execute("UPDATE music_track SET enabled=false; UPDATE surprise_video SET enabled=false");
            schema.execute("INSERT INTO music_track(id,title,artist,audio_asset_id,enabled,sort_order,created_at,updated_at) "
                    +"SELECT 'ok-'||lpad(n::text,4,'0'),'歌曲','', '"+AUDIO+"'::uuid,true,5,now(),now() FROM generate_series(1,2001) n");
            schema.execute("INSERT INTO music_track(id,title,artist,audio_asset_id,enabled,sort_order,created_at,updated_at) "
                    +"VALUES('a-wrong','错误','', '"+IMAGE+"',true,0,now(),now()),('a-empty','无音频','',null,true,0,now(),now())");
            schema.execute("INSERT INTO surprise_video(id,title,video_asset_id,enabled,created_at,updated_at) "
                    +"VALUES('wrong','错误','"+AUDIO+"',true,now(),now()),('valid','有效','"+VIDEO+"',true,now(),now())");
            try(var session=factory(schema.name()).openSession(false)) {
                assertEquals(2001,music(session).findPublicCandidates(2001).size());
                assertEquals(List.of("ok-0001","ok-0002"),music(session).findPublicCandidates(2).stream().map(s->s.track().id()).toList());
                assertEquals(List.of("valid"),videos(session).findPublicCandidates().stream().map(s->s.video().id()).toList());
            }
            schema.execute("UPDATE media_asset SET status='DELETE_PENDING' WHERE id='"+AUDIO+"'; UPDATE media_asset SET status='DELETE_FAILED' WHERE id='"+VIDEO+"'");
            try(var session=factory(schema.name()).openSession(false)) {
                assertTrue(music(session).findPublicCandidates(2001).isEmpty()); assertTrue(videos(session).findPublicCandidates().isEmpty());
                assertEquals(2004,music(session).findAdminList().size()); assertEquals(3,videos(session).findAdminList().size());
            }
        }
    }

    @Test void rollsBackRuntimeUpdate() throws Exception {
        try(var schema=protectedFixture()) {
            var session=springSession(schema.name());
            transactions(schema.name()).executeWithoutResult(s->{
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                music(session).updateMetadata("  Existing-视频-ID  ",new MusicTrackMetadata("回滚","",null,true,0),7,NOW);
                videos(session).updateMetadata("video-1",new SurpriseVideoMetadata("回滚",false,4),7,NOW);
                s.setRollbackOnly();
            });
                assertEquals(7,music(session).findSnapshotById("  Existing-视频-ID  ").orElseThrow().version());
                assertEquals("历史歌",music(session).findSnapshotById("  Existing-视频-ID  ").orElseThrow().track().title());
                assertEquals(7,videos(session).findSnapshotById("video-1").orElseThrow().version());
        }
    }

    @Test void videoCasPreservesAssetAndCreationTime() throws Exception {
        try(var schema=protectedFixture()) {
            var session=springSession(schema.name());
            var repo=videos(session); var before=repo.findSnapshotById("video-1").orElseThrow();
            var tx=transactions(schema.name());
            var saved=tx.execute(s->repo.updateMetadata("video-1",new SurpriseVideoMetadata("新视频",false,3),7,NOW));
            assertEquals(8,saved.version()); assertEquals(UUID.fromString(VIDEO),saved.video().videoAssetId());
            assertEquals(before.createdAt(),saved.createdAt());
            assertEquals(SurpriseCatalogException.Reason.CONFLICT,assertThrows(SurpriseCatalogException.class,
                    ()->tx.execute(s->repo.updateMetadata("video-1",new SurpriseVideoMetadata("覆盖",true,0),7,NOW))).reason());
        }
    }

    @Test void concurrentClientVersionHasExactlyOneWinner() throws Exception {
        try(var schema=protectedFixture()) {
            var pool=Executors.newFixedThreadPool(2);
            var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
            try {
                Future<Boolean> left=pool.submit(()->write(schema.name(),"左",ready,start));
                Future<Boolean> right=pool.submit(()->write(schema.name(),"右",ready,start));
                assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown();
                assertTrue(left.get(10,TimeUnit.SECONDS)^right.get(10,TimeUnit.SECONDS));
                assertEquals(8,music(springSession(schema.name())).findSnapshotById("  Existing-视频-ID  ").orElseThrow().version());
            } finally {start.countDown();pool.shutdownNow();}
        }
    }

    @Test void rejectsNewBoundResourcesAndTranslatesDatabaseFailureSafely() throws Exception {
        try(var schema=fixture();var session=factory(schema.name()).openSession(false)) {
            assertThrows(IllegalArgumentException.class,()->music(session).insertNewMusicTrack(
                    new PersistedMusicTrack("new","曲名","",null,UUID.fromString(AUDIO),null,null,null,true,0),NOW));
            schema.execute("ALTER TABLE music_track RENAME TO unavailable_music");
            var failure=assertThrows(MusicCatalogException.class,()->music(session).findAdminList());
            assertEquals(MusicCatalogException.Reason.UNAVAILABLE,failure.reason()); assertFalse(failure.getMessage().contains("SELECT"));
        }
    }

    static boolean write(String schema,String title,CountDownLatch ready,CountDownLatch start) throws Exception {
        {
            var session=springSession(schema);
            ready.countDown(); start.await();
            try {transactions(schema).execute(s->music(session).updateMetadata("  Existing-视频-ID  ",new MusicTrackMetadata(title,"",null,true,0),7,NOW));return true;}
            catch(MusicCatalogException failure){if(failure.reason()==MusicCatalogException.Reason.CONFLICT)return false;throw failure;}
        }
    }
    static MediaPostgresTestSupport.ScopedSchema fixture() throws Exception {
        var schema=MediaPostgresTestSupport.createSchema();
        try {
            schema.executeDdl();
            schema.execute("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) VALUES "
                    +"('"+AUDIO+"','cloudflare_imgbed','blog/music/中文+100%.mp3','AUDIO','中文+100%.mp3','ACTIVE',now(),now()),"
                    +"('"+IMAGE+"','cloudflare_imgbed','blog/images/cover.png','IMAGE','cover.png','ACTIVE',now(),now()),"
                    +"('"+VIDEO+"','cloudflare_imgbed','blog/surprise/a.mp4','VIDEO','a.mp4','ACTIVE',now(),now())");
            schema.execute("INSERT INTO music_track(id,title,artist,audio_asset_id,cover_asset_id,enabled,sort_order,version,created_at,updated_at) VALUES "
                    +"('  Existing-视频-ID  ','历史歌','', '"+AUDIO+"','"+IMAGE+"',true,0,7,'2026-10-13T00:00:00Z','2026-10-13T00:00:00Z')");
            schema.execute("INSERT INTO surprise_video(id,title,video_asset_id,enabled,sort_order,version,created_at,updated_at) VALUES "
                    +"('video-1','历史视频','"+VIDEO+"',true,0,7,'2026-10-13T00:00:00Z','2026-10-13T00:00:00Z')");
            return schema;
        } catch(Exception failure){schema.close();throw failure;}
    }
    static com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository guard(SqlSession session) {
        var converter=new MediaAssetConverter();
        return new com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaReferenceWriteRepository(
            new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class),converter),
            new com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaDeletionAssetRepository(session.getMapper(com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper.class),new com.blogsystem.infrastructure.mybatis.converter.asset.MediaDeletionConverter(converter)),
            new com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedReferenceLinkGateway(Set.of(java.net.URI.create("https://example.test")),"blog",1048576,10000),
            "cloudflare_imgbed",new com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey("blog"));
    }
    static MybatisMusicTrackRepository music(SqlSession session){return new MybatisMusicTrackRepository(session.getMapper(MusicTrackMapper.class),guard(session));}
    static MybatisSurpriseVideoCatalogRepository videos(SqlSession session){return new MybatisSurpriseVideoCatalogRepository(session.getMapper(SurpriseVideoMapper.class),guard(session));}
    static ScopedSchema protectedFixture() throws Exception {
        var schema=fixture();
        try { MediaDeletionDdlIntegrationTest.migrate(schema); return schema; }
        catch(Exception failure) { schema.close(); throw failure; }
    }
    static final java.util.Map<String,DriverManagerDataSource> springSources=new java.util.concurrent.ConcurrentHashMap<>();
    static DriverManagerDataSource springSource(String schema) { return springSources.computeIfAbsent(schema,MediaCatalogRuntimeApiIntegrationTest::dataSource); }
    static org.springframework.transaction.support.TransactionTemplate transactions(String schema) { return new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(springSource(schema))); }
    static org.mybatis.spring.SqlSessionTemplate springSession(String schema) throws Exception { return MediaCatalogRuntimeApiIntegrationTest.session(springSource(schema)); }
    static SqlSessionFactory factory(String schema) throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("MEDIA_PG_TEST_URL")+"?currentSchema="+schema,System.getenv("MEDIA_PG_TEST_USERNAME"),System.getenv("MEDIA_PG_TEST_PASSWORD"));
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MusicTrackMapper.class);config.addMapper(SurpriseVideoMapper.class);
        config.addMapper(MediaAssetMapper.class);
        config.addMapper(com.blogsystem.infrastructure.mybatis.mapper.asset.MediaDeletionAssetMapper.class);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        factory.setTransactionFactory(new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory());
        return factory.getObject();
    }
}
