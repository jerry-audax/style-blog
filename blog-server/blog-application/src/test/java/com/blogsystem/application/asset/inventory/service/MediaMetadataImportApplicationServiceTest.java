package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.domain.asset.inventory.model.query.MediaImportBatch;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportFailure;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaImportSources;
import com.blogsystem.domain.asset.inventory.repository.MediaImportInputRepository;
import com.blogsystem.domain.asset.inventory.repository.MediaImportSchemaRepository;
import com.blogsystem.domain.asset.metadata.repository.MediaAssetRepository;
import com.blogsystem.domain.music.adapter.repository.MusicTrackMetadataRepository;
import com.blogsystem.domain.surprise.adapter.repository.SurpriseVideoMetadataRepository;
import com.blogsystem.domain.asset.reference.adapter.repository.MediaReferenceWriteRepository;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;
import com.blogsystem.domain.music.model.MusicTrack;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import java.util.Optional;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaMetadataImportApplicationServiceTest {
    @Test void oneBatchGuardPrecedesEveryCatalogInsert() {
        var inputs = mock(MediaImportInputRepository.class);
        var music = mock(MusicTrackMetadataRepository.class);
        var videos = mock(SurpriseVideoMetadataRepository.class);
        var references = mock(MediaReferenceWriteRepository.class);
        var tracks = List.of(new MusicTrack("first", "A", "", "", "", "", "", true, 0),
                new MusicTrack("second", "B", "", "", "", "", "", false, 1));
        when(inputs.read(any())).thenReturn(new MediaImportBatch("cloudflare_imgbed", "blog", List.of(), tracks, List.of(), Map.of(), true));
        for (var track : tracks) {
            when(music.findById(track.id())).thenReturn(Optional.empty(), Optional.of(new PersistedMusicTrack(track.id(), track.title(), "", null, null, null, null, null, track.enabled(), track.sortOrder())));
        }
        when(music.insertIfAbsent(any(), any())).thenReturn(true);
        var transactions = new TransactionOperations() {
            public <T> T execute(TransactionCallback<T> callback) { return callback.doInTransaction(new SimpleTransactionStatus()); }
        };
        var service = new MediaMetadataImportApplicationService(inputs, mock(MediaImportSchemaRepository.class), mock(MediaAssetRepository.class),
                music, videos, references, transactions, Clock.systemUTC());
        String receipt = service.execute(new MediaImportSources("blog_media_test", "public", Path.of("report"), Path.of("music"), Path.of("video"), null, null, null), value -> {
            assertEquals(2, value.tracksCreated()); return "saved";
        });
        assertEquals("saved", receipt);
        var order = inOrder(references, music);
        order.verify(references).lockForWrite(MediaReferenceWriteIntent.empty(), MediaReferenceWriteIntent.empty());
        order.verify(music).insertIfAbsent(argThat(track -> track.id().equals("first")), any());
        order.verify(music).insertIfAbsent(argThat(track -> track.id().equals("second")), any());
        verify(references, times(1)).lockForWrite(any(), any());
    }
    @Test void commitFailureStaysUnknownAndDoesNotAutomaticallyRunAgain() {
        var inputs = mock(MediaImportInputRepository.class);
        var schema = mock(MediaImportSchemaRepository.class);
        when(inputs.read(any())).thenReturn(new MediaImportBatch("cloudflare_imgbed", "blog", List.of(), List.of(), List.of(), Map.of(), true));
        var attempts = new AtomicInteger();
        var transactions = new TransactionOperations() {
            public <T> T execute(TransactionCallback<T> callback) {
                attempts.incrementAndGet();
                callback.doInTransaction(new SimpleTransactionStatus());
                throw new IllegalStateException("fake commit outcome unavailable");
            }
        };
        var service = new MediaMetadataImportApplicationService(inputs, schema, mock(MediaAssetRepository.class),
                mock(MusicTrackMetadataRepository.class), mock(SurpriseVideoMetadataRepository.class), mock(MediaReferenceWriteRepository.class), transactions, Clock.systemUTC());
        var sources = new MediaImportSources("blog_media_test", "public", Path.of("report.json"), Path.of("music.json"),
                Path.of("videos.json"), null, null, null);
        var failure = assertThrows(MediaImportFailure.class, () -> service.execute(sources, receipt -> "receipt"));
        assertEquals(MediaImportFailure.Outcome.COMMIT_UNKNOWN, failure.outcome());
        assertEquals("COMMIT", failure.stage());
        assertEquals(1, attempts.get());
        verify(inputs).read(sources);
    }
}
