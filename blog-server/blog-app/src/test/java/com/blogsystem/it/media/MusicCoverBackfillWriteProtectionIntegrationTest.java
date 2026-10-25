package com.blogsystem.it.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blogsystem.app.it.application.asset.upload.service.FakeMediaUploadStorageRepository;
import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.application.music.service.MusicCoverBackfillApplicationService;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCandidate;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadProbe;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStorageException;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicMediaUpload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(40)
class MusicCoverBackfillWriteProtectionIntegrationTest {
    private static final String TRACK_ID = "  Existing-视频-ID  ";
    private static final UUID OPERATION_ID = UUID.fromString("a7f56d5b-a13d-46d2-a071-3d884ac60001");
    private static final UUID AUDIO_ID = UUID.fromString(MediaRuntimeRepositoryIntegrationTest.AUDIO);
    private static final UUID LYRICS_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID EXTERNAL_COVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final String EXTERNAL_COVER_URL = "https://example.test/file/blog/images/external-cover.png";
    private static final byte[] ARTWORK = {
            (byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0
    };

    @Test
    void backfillPreservesAudioLyricsAndManagedExternalCover() throws Exception {
        try (var fixture = new MediaUploadWorkflowFixture()) {
            seedRetainedReferences(fixture);
            var downloads = new AtomicInteger();
            var uploads = fixture.service();
            var backfill = service(fixture, uploads, downloads);

            var completed = backfill.complete(1, TRACK_ID, 7L, OPERATION_ID);

            var saved = fixture.music.findSnapshotById(TRACK_ID).orElseThrow();
            assertEquals(MediaUploadStatus.SUCCEEDED, completed.progress().status());
            assertEquals(8, saved.version());
            assertEquals(AUDIO_ID, saved.track().audioAssetId());
            assertEquals(LYRICS_ID, saved.track().lyricsAssetId());
            assertEquals(completed.intent().candidateAssetId(), saved.track().coverAssetId());
            assertEquals(EXTERNAL_COVER_URL, saved.track().externalCoverUrl());
            assertEquals("163", saved.track().sourceId());
            assertEquals("历史歌", saved.track().title());
            assertEquals(1, downloads.get());
            assertEquals(List.of("direct"), fixture.storage.calls);
            assertEquals(MediaUploadStatus.SUCCEEDED,
                    fixture.operations.findOwned(1, OPERATION_ID).orElseThrow().progress().status());
        }
    }

    @Test
    void inactiveRetainedAudioConflictsAndReplayDoesNotUploadAgain() throws Exception {
        try (var fixture = new MediaUploadWorkflowFixture()) {
            seedRetainedReferences(fixture);
            var downloads = new AtomicInteger();
            var backfill = service(fixture, fixture.service(), downloads);
            fixture.storage.before = stage -> {
                if (stage.equals("direct")) {
                    try {
                        fixture.execute("UPDATE media_asset SET status='DELETE_PENDING' WHERE id='" + AUDIO_ID + "'");
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
            };

            var conflicted = backfill.complete(1, TRACK_ID, 7L, OPERATION_ID);
            var replay = backfill.complete(1, TRACK_ID, 7L, OPERATION_ID);

            var saved = fixture.music.findSnapshotById(TRACK_ID).orElseThrow();
            assertEquals(MediaUploadStatus.CONFLICT, conflicted.progress().status());
            assertEquals(MediaUploadStatus.CONFLICT, replay.progress().status());
            assertEquals(7, saved.version());
            assertNull(saved.track().coverAssetId());
            assertEquals(AUDIO_ID, saved.track().audioAssetId());
            assertEquals(LYRICS_ID, saved.track().lyricsAssetId());
            assertEquals(EXTERNAL_COVER_URL, saved.track().externalCoverUrl());
            assertTrue(fixture.assets.findById(conflicted.intent().candidateAssetId()).isEmpty());
            assertEquals(1, downloads.get());
            assertEquals(List.of("direct"), fixture.storage.calls);
        }
    }

    @Test
    void recoveryUsesProviderProbeWithoutResendingCover() throws Exception {
        try (var fixture = new MediaUploadWorkflowFixture()) {
            seedRetainedReferences(fixture);
            var downloads = new AtomicInteger();
            MediaUploadApplicationService uploads = fixture.service();
            var backfill = service(fixture, uploads, downloads);
            fixture.storage.before = stage -> {
                if (stage.equals("direct"))
                    throw new MediaUploadStorageException(
                            MediaUploadStorageException.Outcome.UNKNOWN,
                            MediaUploadErrorCode.PROVIDER_UNKNOWN);
            };

            var unknown = backfill.complete(1, TRACK_ID, 7L, OPERATION_ID);
            var receipt = FakeMediaUploadStorageRepository.receipt(unknown.intent());
            fixture.storage.probeResult = new MediaUploadProbe(true, List.of(
                    new MediaUploadCandidate(receipt.objectKey(), MediaKind.IMAGE, (long) ARTWORK.length,
                            "telegram", "configured")));

            var recovered = uploads.recover(1, OPERATION_ID, unknown.version());
            var replay = backfill.complete(1, TRACK_ID, 7L, OPERATION_ID);

            var saved = fixture.music.findSnapshotById(TRACK_ID).orElseThrow();
            assertEquals(MediaUploadStatus.UNKNOWN, unknown.progress().status());
            assertEquals(MediaUploadStatus.SUCCEEDED, recovered.progress().status());
            assertEquals(MediaUploadStatus.SUCCEEDED, replay.progress().status());
            assertEquals(recovered.intent().candidateAssetId(), saved.track().coverAssetId());
            assertEquals(AUDIO_ID, saved.track().audioAssetId());
            assertEquals(LYRICS_ID, saved.track().lyricsAssetId());
            assertEquals(EXTERNAL_COVER_URL, saved.track().externalCoverUrl());
            assertEquals(1, downloads.get());
            assertEquals(List.of("direct", "probe"), fixture.storage.calls);
        }
    }

    private static MusicCoverBackfillApplicationService service(
            MediaUploadWorkflowFixture fixture,
            MediaUploadApplicationService uploads,
            AtomicInteger downloads) {
        return new MusicCoverBackfillApplicationService(
                fixture.music,
                sourceId -> {
                    assertEquals("163", sourceId);
                    downloads.incrementAndGet();
                    return Optional.of(new MusicMediaUpload(ARTWORK, "image/png", "163.png", MusicMediaKind.COVER));
                },
                fixture.operations,
                uploads);
    }

    private static void seedRetainedReferences(MediaUploadWorkflowFixture fixture) throws Exception {
        fixture.execute("INSERT INTO media_asset(id,provider,object_key,media_kind,file_name,status,created_at,updated_at) "
                + "VALUES ('" + LYRICS_ID + "','cloudflare_imgbed','blog/music/lyrics.lrc','LYRICS','lyrics.lrc','ACTIVE',now(),now()),"
                + "('" + EXTERNAL_COVER_ID + "','cloudflare_imgbed','blog/images/external-cover.png','IMAGE','external-cover.png','ACTIVE',now(),now())");
        fixture.execute("UPDATE music_track SET cover_asset_id=NULL,lyrics_asset_id='" + LYRICS_ID
                + "',external_cover_url='" + EXTERNAL_COVER_URL + "',source_id='163' WHERE id='" + TRACK_ID + "'");
    }
}
