package com.blogsystem.app.it.application.music.service;

import com.blogsystem.application.music.service.PersistentMusicCatalogApplicationService;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaAssetStatus;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.music.gateway.SongArtworkGateway;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.domain.music.model.MusicPolicy;
import com.blogsystem.domain.music.model.MusicTrackMetadata;
import com.blogsystem.domain.music.model.MusicTrackSnapshot;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.domain.music.adapter.repository.PlaylistSnapshotRepository;

import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.transaction.support.TransactionOperations;

import com.blogsystem.app.it.application.asset.metadata.service.MediaCatalogTestAssets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blogsystem.domain.music.model.MusicCatalogException.Reason.*;

class PersistentMusicCatalogApplicationServiceTest {
    static final Instant TIME=Instant.parse("2026-10-13T00:00:01Z");
    final MediaCatalogTestAssets assets=new MediaCatalogTestAssets();
    final FakeRepo repo=new FakeRepo(); final Tx tx=new Tx();
    final PlaylistSnapshotRepository snapshots=new PlaylistSnapshotRepository(){
        public Optional<MusicPlaylist> find(){throw new AssertionError("Database reads must not restore unpersisted snapshot covers");}
        public void save(MusicPlaylist p){throw new AssertionError("GET must not write snapshot");}
        public void clear(){throw new AssertionError("GET must not clear snapshot");}};
    SongArtworkGateway artwork=ids->{assertFalse(tx.active);return Map.of();};
    PersistentMusicCatalogApplicationService service(){return new PersistentMusicCatalogApplicationService(repo,assets.query(),snapshots,artwork,new MusicPolicy("939817038"),Clock.fixed(TIME,ZoneOffset.UTC),tx);}
    MusicTrackSnapshot seed(long version,String source,UUID audio,UUID cover,String external){
        var value=new MusicTrackSnapshot(new PersistedMusicTrack("  Existing-视频-ID  ","曲名","歌手",source,audio,cover,null,external,true,0),version,Instant.EPOCH,Instant.EPOCH);repo.rows.put(value.track().id(),value);return value;
    }
    @Test void dbReadNeverImportsOrWrites(){
        var original=seed(7,null,assets.add(MediaKind.AUDIO,MediaAssetStatus.ACTIVE,null,null),null,null);
        var rows=service().catalog();var playlist=service().playlist();
        assertEquals(7L,rows.get(0).version());assertNull(rows.get(0).audio().size());
        assertEquals("imgbed",playlist.source());assertEquals(original.track().id(),playlist.songs().get(0).id());
        assertEquals("",playlist.songs().get(0).mediaType());assertEquals(0,playlist.songs().get(0).size());
        assertEquals(0,repo.writes);assertEquals(0,tx.calls);assertNull(assets.rows.get(original.track().audioAssetId()).metadata().fileSize());
    }
    @Test void missingPersistedCoverDoesNotReadProviderOrHideAudio(){
        seed(7,"163",assets.add(MediaKind.AUDIO,MediaAssetStatus.ACTIVE,16L,"audio/mpeg"),null,null);
        artwork=ids->{throw new AssertionError("Database reads must not request provider artwork");};
        var song=service().playlist().songs().get(0);assertEquals("",song.cover());assertEquals(16,song.size());assertEquals("audio/mpeg",song.mediaType());
        var track=service().catalog().get(0);assertNull(track.cover());assertEquals("163",track.sourceId());
    }
    @Test void onlyPersistedCustomThenPersistedExternalCoverIsDisplayed(){
        UUID cover=assets.add(MediaKind.IMAGE,MediaAssetStatus.ACTIVE,0L,"image/png");
        var seed=seed(7,"163",assets.add(MediaKind.AUDIO,MediaAssetStatus.ACTIVE,0L,"audio/mpeg"),cover,"https://example.test/external.png");
        artwork=ids->Map.of("163","https://example.test/artwork.png");
        assertTrue(service().playlist().songs().get(0).cover().contains("/file/blog/media/"));
        repo.rows.put(seed.track().id(),new MusicTrackSnapshot(new PersistedMusicTrack(seed.track().id(),"曲名","", "163",seed.track().audioAssetId(),null,null,"https://example.test/external.png",true,0),7,Instant.EPOCH,Instant.EPOCH));
        assertEquals("https://example.test/external.png",service().playlist().songs().get(0).cover());
        seed(7,"163",seed.track().audioAssetId(),null,null);
        assertEquals("",service().playlist().songs().get(0).cover());
        assertNull(service().catalog().get(0).cover());
        assertEquals("163",repo.rows.get(seed.track().id()).track().sourceId());
    }
    @Test void staleClientCannotOverwrite(){
        var seed=seed(7,null,null,null,null);
        assertEquals(CONFLICT,assertThrows(MusicCatalogException.class,()->service().updateMetadata(seed.track().id(),"旧编辑","",null,true,0,6L)).reason());
        assertEquals(MISSING_VERSION,assertThrows(MusicCatalogException.class,()->service().updateMetadata(seed.track().id(),"编辑","",null,true,0,null)).reason());
        assertEquals(0,repo.writes);assertEquals(0,tx.calls);
    }
    @Test void missingAndDeletedIdsCannotBeCreatedByPut(){
        assertEquals(NOT_FOUND,assertThrows(MusicCatalogException.class,()->service().updateMetadata("deleted","新歌","",null,true,0,7L)).reason());
        assertEquals(0,repo.writes);assertTrue(repo.rows.isEmpty());
    }
    @Test void resourceOperationsAreUnavailable(){
        assertEquals(OPERATION_NOT_READY,assertThrows(MusicCatalogException.class,()->service().upload("id",null)).reason());
        assertEquals(OPERATION_NOT_READY,assertThrows(MusicCatalogException.class,()->service().deleteMedia("id",MusicMediaKind.AUDIO)).reason());
        assertEquals(OPERATION_NOT_READY,assertThrows(MusicCatalogException.class,()->service().deleteTrack("id")).reason());
        assertEquals(0,assets.reads);assertEquals(0,repo.reads);assertEquals(0,tx.calls);
    }
    @Test void writeReturnsPreparedProjectionWithoutPostCommitReads(){
        var seed=seed(7,"163",assets.add(MediaKind.AUDIO,MediaAssetStatus.ACTIVE,6L,"audio/mpeg"),null,null);
        tx.after=()->assets.fail=true;
        var saved=service().updateMetadata(seed.track().id(),"新歌","新歌手","163",false,3,7L);
        assertEquals(8L,saved.version());assertEquals("新歌",saved.title());assertEquals(6L,saved.audio().size());
        assertEquals(seed.track().audioAssetId(),repo.rows.get(seed.track().id()).track().audioAssetId());assertEquals(1,tx.calls);
    }
    @Test void createsOnlyOneRowAndVersionZeroIsValid(){
        seed(7,null,null,null,null);var saved=service().createMetadata(null,"新歌",null,null,true,0);
        assertTrue(saved.id().matches("[a-f0-9]{32}"));assertEquals(0L,saved.version());assertNull(saved.audio());assertEquals(2,repo.rows.size());
        assertEquals(1L,service().updateMetadata(saved.id(),"编辑","",null,true,0,0L).version());
        assertEquals(INVALID_REQUEST,assertThrows(MusicCatalogException.class,()->service().createMetadata("中文","x","",null,true,0)).reason());
    }
    @Test void emptyAndUnavailableRemainDistinctAndOversizeNeverTruncates(){
        assertTrue(service().playlist().songs().isEmpty());repo.fail=true;
        assertEquals(UNAVAILABLE,assertThrows(MusicCatalogException.class,()->service().playlist()).reason());repo.fail=false;
        var seed=seed(7,null,assets.add(MediaKind.AUDIO,MediaAssetStatus.ACTIVE,null,null),null,null);
        repo.publicRows=Collections.nCopies(2001,seed);
        assertEquals(TOO_LARGE,assertThrows(MusicCatalogException.class,()->service().playlist()).reason());
    }
    @Test void referencesIncludeDisabledAndInactiveUsingExpectedRoleKind(){
        var seed=seed(7,null,assets.add(MediaKind.IMAGE,MediaAssetStatus.DELETE_PENDING,null,null),null,null);
        repo.rows.put(seed.track().id(),new MusicTrackSnapshot(new PersistedMusicTrack(seed.track().id(),"曲名","",null,seed.track().audioAssetId(),null,null,null,false,0),7,Instant.EPOCH,Instant.EPOCH));
        var refs=service().listMediaReferences();assertEquals(1,refs.size());assertEquals(MediaKind.AUDIO,refs.get(0).kind());assertEquals(seed.track().id(),refs.get(0).ownerId());
    }
    static class Tx implements TransactionOperations {
        boolean active;int calls;Runnable after=()->{};
        public <T>T execute(TransactionCallback<T> action){calls++;active=true;T result;try{result=action.doInTransaction(new SimpleTransactionStatus());}finally{active=false;}after.run();return result;}
    }
    static class FakeRepo implements MusicTrackRepository {
        final Map<String,MusicTrackSnapshot> rows=new LinkedHashMap<>();int reads,writes;boolean fail;List<MusicTrackSnapshot> publicRows;
        public List<MusicTrackSnapshot> findAdminList(){reads++;if(fail)throw new MusicCatalogException(UNAVAILABLE);return List.copyOf(rows.values());}
        public Optional<MusicTrackSnapshot> findSnapshotById(String id){reads++;return Optional.ofNullable(rows.get(id));}
        public List<MusicTrackSnapshot> findPublicCandidates(int limit){assertEquals(2001,limit);return publicRows==null?findAdminList():publicRows;}
        public MusicTrackSnapshot insertNewMusicTrack(PersistedMusicTrack track,Instant now){writes++;if(rows.containsKey(track.id()))throw new MusicCatalogException(DUPLICATE);var saved=new MusicTrackSnapshot(track,0,now,now);rows.put(track.id(),saved);return saved;}
        public MusicTrackSnapshot updateMetadata(String id,MusicTrackMetadata m,long version,Instant now){
            var old=rows.get(id);if(old==null)throw new MusicCatalogException(NOT_FOUND);if(old.version()!=version)throw new MusicCatalogException(CONFLICT);writes++;
            var t=old.track();var saved=new MusicTrackSnapshot(new PersistedMusicTrack(id,m.title(),m.artist(),m.sourceId(),t.audioAssetId(),t.coverAssetId(),t.lyricsAssetId(),t.externalCoverUrl(),m.enabled(),m.sortOrder()),version+1,old.createdAt(),now);rows.put(id,saved);return saved;
        }
    }
}
