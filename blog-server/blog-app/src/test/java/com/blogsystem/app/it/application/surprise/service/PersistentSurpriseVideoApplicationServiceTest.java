package com.blogsystem.app.it.application.surprise.service;

import com.blogsystem.application.surprise.service.PersistentSurpriseVideoApplicationService;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.surprise.model.PersistedSurpriseVideo;
import com.blogsystem.domain.surprise.model.SurpriseCatalogException;
import com.blogsystem.domain.surprise.model.SurpriseVideoMetadata;
import com.blogsystem.domain.surprise.model.SurpriseVideoSnapshot;

import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.support.TransactionOperations;

import com.blogsystem.app.it.application.asset.metadata.service.MediaCatalogTestAssets;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoCatalogRepository;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blogsystem.domain.surprise.model.SurpriseCatalogException.Reason.*;

class PersistentSurpriseVideoApplicationServiceTest {
    final MediaCatalogTestAssets assets=new MediaCatalogTestAssets();final Repo repo=new Repo();
    final TransactionOperations tx=new TransactionOperations(){public <T>T execute(TransactionCallback<T> action){T saved=action.doInTransaction(new SimpleTransactionStatus());assets.fail=true;return saved;}};
    PersistentSurpriseVideoApplicationService service(){return new PersistentSurpriseVideoApplicationService(repo,assets.query(),Clock.fixed(Instant.ofEpochSecond(1),ZoneOffset.UTC),tx);}
    SurpriseVideoSnapshot seed(long version){var video=new PersistedSurpriseVideo("  Existing-视频-ID  ","历史视频",assets.add(MediaKind.VIDEO,MediaAssetStatus.ACTIVE,null,null),true,0);var s=new SurpriseVideoSnapshot(video,version,Instant.EPOCH,Instant.EPOCH);repo.rows.put(video.id(),s);return s;}
    @Test void dbReadNeverImportsOrWrites(){var seed=seed(7);assertEquals(7L,service().adminCatalog().get(0).version());assertNull(service().adminCatalog().get(0).size());assertEquals(seed.video().id(),service().randomPublic().id());assertEquals(0,service().randomPublic().size());assertEquals(0,repo.writes);}
    @Test void staleClientCannotOverwrite(){var seed=seed(7);assertEquals(CONFLICT,assertThrows(SurpriseCatalogException.class,()->service().updateMetadata(seed.video().id(),"x",null,null,6L)).reason());assertEquals(MISSING_VERSION,assertThrows(SurpriseCatalogException.class,()->service().updateMetadata(seed.video().id(),"x",null,null,null)).reason());assertEquals(0,repo.writes);}
    @Test void missingAndDeletedIdsCannotBeCreatedByPut(){assertEquals(NOT_FOUND,assertThrows(SurpriseCatalogException.class,()->service().updateMetadata("missing","x",true,0,7L)).reason());assertTrue(repo.rows.isEmpty());}
    @Test void resourceOperationsAreUnavailable(){assertEquals(OPERATION_NOT_READY,assertThrows(SurpriseCatalogException.class,()->service().create("x",true,0,null)).reason());assertEquals(OPERATION_NOT_READY,assertThrows(SurpriseCatalogException.class,()->service().delete("id")).reason());assertEquals(0,repo.writes);assertEquals(0,assets.reads);}
    @Test void optionalPatchPreservesFieldsAndUsesPreparedProjection(){var seed=seed(7);var saved=service().updateMetadata(seed.video().id(),null,false,null,7L);assertEquals(8L,saved.version());assertEquals("历史视频",saved.title());assertFalse(saved.enabled());assertEquals(0,saved.sortOrder());assertNull(saved.size());assertEquals(seed.video().videoAssetId(),repo.rows.get(seed.video().id()).video().videoAssetId());}
    @Test void emptyCatalogDoesNotInventVideosAndRandomFails(){assertTrue(service().adminCatalog().isEmpty());assertEquals(UNAVAILABLE,assertThrows(SurpriseCatalogException.class,()->service().randomPublic()).reason());assertEquals(0,repo.writes);}
    @Test void referencesIncludeDisabledAndInactive(){var seed=seed(7);var asset=seed.video().videoAssetId();repo.rows.put(seed.video().id(),new SurpriseVideoSnapshot(new PersistedSurpriseVideo(seed.video().id(),"视频",asset,false,0),7,Instant.EPOCH,Instant.EPOCH));assertEquals(MediaKind.VIDEO,service().listMediaReferences().get(0).kind());assertEquals(seed.video().id(),service().listMediaReferences().get(0).ownerId());}
    static class Repo implements SurpriseVideoCatalogRepository {
        final Map<String,SurpriseVideoSnapshot> rows=new LinkedHashMap<>();int writes;
        public List<SurpriseVideoSnapshot> findAdminList(){return List.copyOf(rows.values());}
        public Optional<SurpriseVideoSnapshot> findSnapshotById(String id){return Optional.ofNullable(rows.get(id));}
        public List<SurpriseVideoSnapshot> findPublicCandidates(){return findAdminList();}
        public SurpriseVideoSnapshot updateMetadata(String id,SurpriseVideoMetadata m,long version,Instant now){var old=rows.get(id);if(old==null)throw new SurpriseCatalogException(NOT_FOUND);if(old.version()!=version)throw new SurpriseCatalogException(CONFLICT);writes++;var saved=new SurpriseVideoSnapshot(new PersistedSurpriseVideo(id,m.title(),old.video().videoAssetId(),m.enabled(),m.sortOrder()),version+1,old.createdAt(),now);rows.put(id,saved);return saved;}
    }
}
