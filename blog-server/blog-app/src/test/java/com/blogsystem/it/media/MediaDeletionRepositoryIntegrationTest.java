package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.domain.asset.deletion.model.MediaDeletionException;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation;
import com.blogsystem.domain.asset.deletion.model.entity.MediaDeletionOperation.*;
import com.blogsystem.domain.asset.deletion.model.valobj.*;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.infrastructure.adapter.repository.asset.*;
import com.blogsystem.infrastructure.mybatis.converter.asset.*;
import com.blogsystem.infrastructure.mybatis.mapper.asset.*;
import com.blogsystem.infrastructure.mybatis.schema.MediaDeletionSchemaVerifier;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaDeletionRepositoryIntegrationTest {
    static DriverManagerDataSource dataSource(String schema) {
        return new DriverManagerDataSource(System.getenv("MEDIA_PG_TEST_URL")+"?currentSchema="+schema,System.getenv("MEDIA_PG_TEST_USERNAME"),System.getenv("MEDIA_PG_TEST_PASSWORD"));
    }
    static SqlSessionFactory factory(String schema) throws Exception {
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MediaAssetMapper.class);config.addMapper(MediaDeletionAssetMapper.class);config.addMapper(MediaDeletionOperationMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setConfiguration(config);factory.setDataSource(dataSource(schema));
        factory.setTransactionFactory(new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory());return factory.getObject();
    }
    static MediaDeletionConverter converter(){return new MediaDeletionConverter(new MediaAssetConverter());}
    static MybatisMediaDeletionAssetRepository assets(SqlSession s){return new MybatisMediaDeletionAssetRepository(s.getMapper(MediaDeletionAssetMapper.class),converter());}
    static MybatisMediaDeletionOperationRepository operations(SqlSession s){return new MybatisMediaDeletionOperationRepository(s.getMapper(MediaDeletionOperationMapper.class),converter());}
    @Test void ownershipCasPreservesMetadataAndCannotChangeDeletingResource() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.fixture()) {
            MediaDeletionDdlIntegrationTest.migrate(schema);new MediaDeletionSchemaVerifier(dataSource(schema.name())).verify();
            try(var s=factory(schema.name()).openSession(false)) {
                UUID id=UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO);
                assertEquals(id,s.getMapper(MediaDeletionAssetMapper.class).selectById(id).getId());
                var repo=assets(s);var original=repo.findById(id).orElseThrow();
                assertEquals(MediaDeletionOwnership.Scope.UNVERIFIED,original.ownership().scope());
                var now=Instant.now().plusSeconds(1);var ownership=new MediaDeletionOwnership(MediaDeletionOwnership.Scope.EXCLUSIVE,"local",7L,now,"已核对用途");
                var saved=repo.confirmOwnership(id,ownership,0,now);assertEquals(1,saved.asset().version());assertEquals(original.asset().metadata(),saved.asset().metadata());s.commit();
                assertEquals(MediaDeletionException.Reason.CONFLICT,assertThrows(MediaDeletionException.class,()->repo.confirmOwnership(id,ownership,0,now)).reason());
                var ordinary=new MybatisMediaAssetRepository(s.getMapper(MediaAssetMapper.class),new MediaAssetConverter());
                ordinary.updateMetadata(saved.asset().reviseMetadata(saved.asset().metadata(),now.plusSeconds(1)),1);s.commit(true);
                assertEquals(ownership,repo.findById(id).orElseThrow().ownership());
                assertEquals(List.of(id),repo.lockByIds(Set.of(id)).stream().map(v->v.asset().id()).toList());s.commit(true);
                schema.execute("UPDATE media_asset SET status='DELETE_PENDING' WHERE id='"+id+"'");
                assertThrows(MediaDeletionException.class,()->repo.confirmOwnership(id,ownership,2,now.plusSeconds(2)));
                var fresh=com.blogsystem.domain.asset.metadata.model.entity.MediaAsset.register(UUID.randomUUID(),"cloudflare_imgbed",
                        new com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey("blog/music/new-upload.mp3"),MediaKind.AUDIO,
                        new com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata("new-upload.mp3",null,null,null,null,null,null),now);
                ordinary.insert(fresh);s.commit();
                assertEquals(MediaDeletionOwnership.Scope.UNVERIFIED,repo.findById(fresh.id()).orElseThrow().ownership().scope());
            }
        }
    }
    @Test void roundTripsReceiptsEnforcesOwnerAndStablePaginationIncludingZeroItems() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.fixture()) {
            MediaDeletionDdlIntegrationTest.migrate(schema);
            try(var s=factory(schema.name()).openSession(false)) {
                var repo=operations(s);UUID asset=UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO);
                var item=new Item(UUID.randomUUID(),asset,MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.RETAINED,Reason.OWNERSHIP_UNVERIFIED,null);
                var target=new MediaDeletionTarget(MediaDeletionTarget.Kind.MUSIC_TRACK,"  Existing-视频-ID  ",null);
                Instant now=Instant.now();var op=new MediaDeletionOperation(UUID.randomUUID(),7,UUID.randomUUID().toString(),"b".repeat(64),target,9007199254740993L,now,List.of(item));
                repo.insert(op);s.commit();
                assertEquals(asset,s.getMapper(MediaDeletionOperationMapper.class).selectItems(op.id()).get(0).getAssetId());
                assertEquals(op,repo.findById(op.id(),7).orElseThrow());
                assertTrue(repo.findById(op.id(),8).isEmpty());assertEquals(op,repo.findByActorAndKey(7,op.idempotencyKey()).orElseThrow());
                var empty=new MediaDeletionOperation(UUID.randomUUID(),7,UUID.randomUUID().toString(),"c".repeat(64),target,0,now.plusSeconds(1),List.of());repo.insert(empty);s.commit();
                assertEquals(List.of(empty.id(),op.id()),repo.findPage(7,0,20).records().stream().map(MediaDeletionOperation::id).toList());assertEquals(2,repo.findPage(7,1,1).total());assertEquals(0,repo.findPage(8,0,20).total());
                assertFalse(repo.findById(empty.id(),7).orElseThrow().allFilesDeleted());
                assertThrows(MediaDeletionException.class,()->repo.insert(op));s.rollback();
                assertThrows(IllegalArgumentException.class,()->repo.findPage(7,0,101));
            }
        }
    }
    @Test void linkedReceiptProjectsCanonicalResultInFreshQueries() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.fixture()) {
            MediaDeletionDdlIntegrationTest.migrate(schema);
            UUID asset=UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO);
            var canonical=new Item(UUID.randomUUID(),asset,MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.PENDING,Reason.NONE,null);
            var linked=new Item(UUID.randomUUID(),asset,MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.LINKED,Reason.NONE,canonical);
            var target=new MediaDeletionTarget(MediaDeletionTarget.Kind.MUSIC_TRACK,"song",null);
            var parent=new MediaDeletionOperation(UUID.randomUUID(),7,UUID.randomUUID().toString(),"a".repeat(64),target,0,Instant.now(),List.of(canonical));
            var child=new MediaDeletionOperation(UUID.randomUUID(),7,UUID.randomUUID().toString(),"b".repeat(64),target,0,Instant.now(),List.of(linked));
            try(var s=factory(schema.name()).openSession(false)) {
                operations(s).insert(parent);operations(s).insert(child);s.commit();
                assertFalse(operations(s).findById(child.id(),7).orElseThrow().processingComplete());
            }
            schema.execute("UPDATE media_delete_item SET status='SUCCEEDED' WHERE id='"+canonical.id()+"'");
            try(var s=factory(schema.name()).openSession(false)) {
                var result=operations(s).findById(child.id(),7).orElseThrow();
                assertTrue(result.processingComplete());assertTrue(result.allFilesDeleted());
                assertEquals(ItemStatus.SUCCEEDED,result.items().get(0).resolvedStatus());
            }
        }
    }
    @Test void receiptItemOrderingIsStableAcrossSaveAndReplay() throws Exception {
        try(var schema=MediaRuntimeRepositoryIntegrationTest.fixture()) {
            MediaDeletionDdlIntegrationTest.migrate(schema);
            var audio=new Item(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO),MediaKind.AUDIO,0,MediaDeletionOwnership.unverified(),ItemStatus.RETAINED,Reason.OWNERSHIP_UNVERIFIED,null);
            var cover=new Item(UUID.fromString("00000000-0000-0000-0000-000000000000"),UUID.fromString(MediaRuntimeRepositoryIntegrationTest.IMAGE),MediaKind.IMAGE,0,MediaDeletionOwnership.unverified(),ItemStatus.RETAINED,Reason.OWNERSHIP_UNVERIFIED,null);
            var op=new MediaDeletionOperation(UUID.randomUUID(),7,UUID.randomUUID().toString(),"a".repeat(64),new MediaDeletionTarget(MediaDeletionTarget.Kind.MUSIC_TRACK,"song",null),0,Instant.now(),List.of(audio,cover));
            try(var s=factory(schema.name()).openSession(false)) {
                operations(s).insert(op);s.commit();
                assertEquals(List.of(audio.assetId(),cover.assetId()),operations(s).findByActorAndKey(7,op.idempotencyKey()).orElseThrow().items().stream().map(Item::assetId).toList());
            }
        }
    }
}
